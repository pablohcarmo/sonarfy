package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.FollowingController;
import br.com.pablohcarmo.sonarfy.entities.*;
import br.com.pablohcarmo.sonarfy.repositories.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Usa somente o PostgreSQL descartável dedicado; nunca lê a configuração da aplicação.
@EnabledIfSystemProperty(named = "sonarfy.test.following-postgres", matches = "true")
@SpringJUnitWebConfig(FollowingPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=following-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class FollowingPostgresTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, FollowingController.class, FollowingService.class,
            br.com.pablohcarmo.sonarfy.controllers.ProfileController.class, ProfileService.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override DataSource dataSource(org.springframework.core.env.Environment env) {
            return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55451/sonarfy_following_test",
                    "following_test", "isolated-following-test");
        }
        @Bean @Override org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired JwtService jwt;
    @Autowired FollowingService service;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    JdbcTemplate db;
    User actor, target, other;

    @BeforeEach
    void prepare() {
        db = new JdbcTemplate(context.getBean(DataSource.class));
        db.execute("delete from user_following");
        users.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        actor = account("actor"); target = account("target"); other = account("other");
    }

    // Repetições preservam a data e a exclusão afeta somente a direção autenticada.
    @Test void idempotencyAndIsolation() throws Exception {
        call(true, actor, target, 204); Object date = db.queryForObject("select follow_date from user_following", Object.class);
        call(true, actor, target, 204); assertEquals(date, db.queryForObject("select follow_date from user_following", Object.class));
        call(true, other, target, 204); call(true, target, actor, 204);
        mvc.perform(put(path(target)).header("Authorization", bearer(actor)).contentType("application/json")
                .content("{\"actorId\":" + other.getId() + "}" )).andExpect(status().isNoContent()).andExpect(content().string(""));
        call(false, actor, target, 204); call(false, actor, target, 204);
        assertEquals(2, count()); assertEquals(0, relation(actor, target));
        assertEquals(1, relation(other, target)); assertEquals(1, relation(target, actor));
        for (boolean follow : new boolean[]{true, false}) call(follow, actor, actor, 400);
    }

    // Perfis inelegíveis não permitem criar nem apagar relações existentes.
    @Test void eligibilityAndReactivation() throws Exception {
        for (User user : new User[]{actor, target}) {
            for (String column : new String[]{"active", "verified"}) {
                call(true, actor, target, 204);
                db.update("update users set " + column + " = false where user_id = ?", user.getId());
                for (boolean follow : new boolean[]{true, false}) call(follow, actor, target, user == actor ? 401 : 404);
                assertEquals(1, relation(actor, target));
                db.update("update users set " + column + " = true where user_id = ?", user.getId());
                call(false, actor, target, 204);
                db.update("update users set active = false where user_id = ?", user.getId());
                call(true, actor, target, user == actor ? 401 : 404);
                db.update("update users set active = true where user_id = ?", user.getId());
                assertEquals(0, count());
            }
        }
        for (boolean follow : new boolean[]{true, false})
            mvc.perform(request(follow ? org.springframework.http.HttpMethod.PUT : org.springframework.http.HttpMethod.DELETE,
                    "/api/profile/9223372036854775807/following").header("Authorization", bearer(actor))).andExpect(status().isNotFound());
    }

    // Exercita o filtro real sem substituir a autenticação por um principal artificial.
    @Test void credentials() throws Exception {
        String expired = com.auth0.jwt.JWT.create().withIssuer("sonarfy-api").withSubject(actor.getId().toString())
                .withClaim("purpose", "access").withExpiresAt(java.time.Instant.now().minusSeconds(60))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("following-test-secret"));
        for (boolean follow : new boolean[]{true, false}) {
            mvc.perform(request(follow ? org.springframework.http.HttpMethod.PUT : org.springframework.http.HttpMethod.DELETE, path(target)))
                    .andExpect(status().isUnauthorized());
            for (String token : new String[]{"invalid", expired, jwt.generateRegistrationToken(actor.getId().toString())})
                mvc.perform(request(follow ? org.springframework.http.HttpMethod.PUT : org.springframework.http.HttpMethod.DELETE, path(target))
                        .header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        assertEquals(0, count());
    }

    // Chamadas simultâneas nas duas direções usam a mesma ordem de bloqueios.
    @Test void concurrentMutations() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1); var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 24; i++) {
                int n = i;
                futures.add(pool.submit(() -> { start.await(); service.change(n % 2 == 0 ? actor.getId() : target.getId(),
                        n % 2 == 0 ? target.getId() : actor.getId(), n % 3 != 0); return null; }));
            }
            start.countDown(); for (var future : futures) future.get(15, TimeUnit.SECONDS);
            service.change(actor.getId(), target.getId(), true); service.change(target.getId(), actor.getId(), true);
            assertEquals(2, count());
        } finally { pool.shutdownNow(); }
    }

    // A desativação bloqueada no banco força a releitura após a autenticação e preserva a relação.
    @Test void waitsForDeactivationAndRechecksBothAccounts() throws Exception {
        for (User user : new User[]{actor, target}) for (boolean follow : new boolean[]{true, false}) {
            service.change(actor.getId(), target.getId(), true);
            var pool = Executors.newSingleThreadExecutor();
            try {
                Future<?>[] pending = new Future<?>[1];
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    db.update("update users set active = false where user_id = ?", user.getId());
                    pending[0] = pool.submit(() -> service.change(actor.getId(), target.getId(), follow));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (System.nanoTime() < deadline && db.queryForObject(
                            "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Integer.class) == 0)
                        db.execute("select pg_stat_clear_snapshot()");
                    assertFalse(pending[0].isDone());
                    assertTrue(db.queryForObject("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Integer.class) > 0);
                });
                var failure = assertThrows(ExecutionException.class, () -> pending[0].get(10, TimeUnit.SECONDS));
                assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, failure.getCause());
                assertEquals(1, relation(actor, target));
                db.update("update users set active = true where user_id = ?", user.getId());
            } finally { pool.shutdownNow(); }
        }
    }

    // A leitura HTTP reflete as mutações confirmadas sem confundir o visitante com o alvo.
    @Test void readsCommittedMutationsOverHttp() throws Exception {
        for (int repeat = 0; repeat < 2; repeat++) {
            call(true, actor, target, 204);
            mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer(actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(true))
                    .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(0));
            mvc.perform(get("/api/profile/me").header("Authorization", bearer(actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false))
                    .andExpect(jsonPath("$.statistics.followers").value(0)).andExpect(jsonPath("$.statistics.following").value(1));
            mvc.perform(get("/api/profile/by-handle/actor").header("Authorization", bearer(target)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false));
        }
        for (int repeat = 0; repeat < 2; repeat++) {
            call(false, actor, target, 204);
            mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer(actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false))
                    .andExpect(jsonPath("$.statistics.followers").value(0));
            mvc.perform(get("/api/profile/me").header("Authorization", bearer(actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.statistics.following").value(0));
        }
    }

    private String path(User user) { return "/api/profile/" + user.getId() + "/following"; }
    private String bearer(User user) { return "Bearer " + jwt.generateAccessToken(user.getId().toString()); }
    private void call(boolean follow, User from, User to, int statusCode) throws Exception {
        mvc.perform(request(follow ? org.springframework.http.HttpMethod.PUT : org.springframework.http.HttpMethod.DELETE, path(to))
                .header("Authorization", bearer(from))).andExpect(status().is(statusCode));
    }
    private int count() { return db.queryForObject("select count(*) from user_following", Integer.class); }
    private int relation(User from, User to) { return db.queryForObject(
            "select count(*) from user_following where follower_id = ? and following_id = ?", Integer.class, from.getId(), to.getId()); }
    private User account(String handle) {
        Permission role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            var p = new Permission(); p.setName("ROLE_USER"); return permissions.save(p);
        });
        return users.saveAndFlush(new User(null, role, "Private", "Account", handle, handle,
                handle + "@example.com", "private-hash", true, true, "Brasilia", "Brasil", null, null, null,
                LocalDate.of(2000, 1, 1), null, null, null, null));
    }
}

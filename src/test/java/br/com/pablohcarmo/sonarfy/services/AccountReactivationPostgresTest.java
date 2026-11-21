package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.*;
import br.com.pablohcarmo.sonarfy.entities.*;
import br.com.pablohcarmo.sonarfy.repositories.*;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Usa exclusivamente o banco descartável dedicado, com segurança, JPA e SQL reais; SMTP simulado.
@EnabledIfSystemProperty(named = "sonarfy.test.reactivation-postgres", matches = "true")
@SpringJUnitWebConfig(AccountReactivationPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=reactivation-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class AccountReactivationPostgresTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, AccountReactivationController.class, AccountReactivationService.class,
            ReactivationMailService.class, AuthController.class, AuthService.class, ProfileController.class,
            ProfileService.class, FollowingService.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override DataSource dataSource(org.springframework.core.env.Environment env) {
            return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + env.getProperty("sonarfy.test.reactivation-port", "55452") + "/sonarfy_reactivation_test",
                    "reactivation_test", "isolated-reactivation-test");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean @Override org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PasswordEncoder passwords;
    @Autowired RefreshTokenService refresh;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate db;
    @Autowired ReactivationMailService mails;
    @Autowired AccountReactivationService service;
    @Autowired FollowingService following;
    @Autowired EmailService email;
    @Autowired PlatformTransactionManager manager;
    MockMvc mvc;
    User owner, other;

    @BeforeEach void prepare() {
        users.deleteAll(); reset(email, passwords);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account("owner"); other = account("other");
    }
    User account(String handle) {
        User user = new User(); user.setHandle(handle); user.setEmail(handle + "@example.com");
        user.setName("Conta"); user.setSurname("Teste"); user.setDisplayName("Conta Teste");
        user.setCity("Campinas"); user.setCountry("Brasil"); user.setBirthDate(LocalDate.of(1990, 1, 1));
        user.setPassword(passwords.encode("Correct123!")); user.setVerified(true);
        user.setPermission(permissions.findByName("ROLE_USER").orElseThrow());
        return users.saveAndFlush(user);
    }
    ResultActions reactivate(String login, String password) throws Exception {
        return mvc.perform(post("/api/auth/reactivation").header("X-Sonarfy-CSRF", "1")
                .contentType("application/json").content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}"));
    }
    void inactive() { db.update("update users set active = false where user_id = ?", owner.getId()); }
    int notices() { return db.queryForObject("select count(*) from reactivation_mail_outbox", Integer.class); }

    // A transição preserva relações e dados, invalida acessos antigos e permite apenas sessões novas.
    @Test void fullHttpFlowAndIsolation() throws Exception {
        String oldRefresh = refresh.issue(owner), foreignRefresh = refresh.issue(other);
        String oldAccess = jwt.generateAccessToken(owner.getId().toString());
        var delayed = refresh.rotate(refresh.issue(owner));
        following.change(owner.getId(), other.getId(), true); following.change(other.getId(), owner.getId(), true);
        inactive();
        reactivate(" @OWNER ", "Correct123!").andExpect(status().isNoContent())
                .andExpect(content().string("")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(1, notices()); assertThrows(Exception.class, () -> refresh.rotate(oldRefresh));
        mvc.perform(post("/api/auth/refresh").header("X-Sonarfy-CSRF", "1")
                .cookie(new jakarta.servlet.http.Cookie("sonarfy_refresh", oldRefresh))).andExpect(status().isUnauthorized());
        assertNotNull(refresh.rotate(foreignRefresh));
        var reloaded = users.findById(owner.getId()).orElseThrow();
        assertTrue(reloaded.isEnabled()); assertEquals(owner.getEmail(), reloaded.getEmail());
        assertEquals(owner.getPassword(), reloaded.getPassword()); assertEquals(1, reloaded.getAccessTokenVersion());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> refresh.issue(owner, false, 0L));
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + oldAccess)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + jwt.generateAccessToken(
                delayed.userId().toString(), delayed.accessTokenVersion()))).andExpect(status().isUnauthorized());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> refresh.rotate(delayed.refreshToken()));
        String legacy = JWT.create().withIssuer("sonarfy-api").withSubject(owner.getId().toString()).withClaim("purpose", "access")
                .withExpiresAt(Instant.now().plusSeconds(900)).sign(Algorithm.HMAC256("reactivation-test-secret"));
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + legacy)).andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/auth/login").header("X-Sonarfy-CSRF", "1").contentType("application/json")
                .content("{\"login\":\"owner\",\"password\":\"Correct123!\"}")).andExpect(status().isOk()).andReturn();
        String access = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(1))
                .andExpect(jsonPath("$.email").doesNotExist());
        var renewed = mvc.perform(post("/api/auth/refresh").header("X-Sonarfy-CSRF", "1")
                .cookie(org.springframework.mock.web.MockCookie.parse(login.getResponse().getHeader("Set-Cookie"))))
                .andExpect(status().isOk()).andReturn();
        String renewedAccess = new com.fasterxml.jackson.databind.ObjectMapper().readTree(renewed.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + renewedAccess)).andExpect(status().isOk());
        reactivate("owner@example.com", "Correct123!").andExpect(status().isNoContent()); assertEquals(1, notices());
        assertEquals(0, users.findById(other.getId()).orElseThrow().getAccessTokenVersion());
    }

    // Repetir a chamada preserva os tokens válidos e a versão de uma sessão criada após reativação.
    @Test void alreadyActiveAccountPreservesCurrentHttpSession() throws Exception {
        inactive(); reactivate("owner", "Correct123!").andExpect(status().isNoContent());
        var login = mvc.perform(post("/api/auth/login").header("X-Sonarfy-CSRF", "1").contentType("application/json")
                .content("{\"login\":\"owner\",\"password\":\"Correct123!\"}")).andExpect(status().isOk()).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String access = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        var cookie = org.springframework.mock.web.MockCookie.parse(login.getResponse().getHeader("Set-Cookie"));
        for (int i = 0; i < 2; i++) reactivate("owner", "Correct123!").andExpect(status().isNoContent());
        assertEquals(1, users.findById(owner.getId()).orElseThrow().getAccessTokenVersion()); assertEquals(1, notices());
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        var renewed = mvc.perform(post("/api/auth/refresh").header("X-Sonarfy-CSRF", "1").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        String renewedAccess = json.readTree(renewed.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + renewedAccess)).andExpect(status().isOk());
        verifyNoInteractions(email);
    }

    // O mesmo JWT legado é aceito na versão zero e recusado por HTTP após a transição para a versão um.
    @Test void legacyAccessTokenWorksOnlyUntilFirstReactivation() throws Exception {
        String legacy = JWT.create().withIssuer("sonarfy-api").withSubject(owner.getId().toString()).withClaim("purpose", "access")
                .withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(900))
                .sign(Algorithm.HMAC256("reactivation-test-secret"));
        assertTrue(JWT.decode(legacy).getClaim("access_version").isMissing());
        assertEquals(0, users.findById(owner.getId()).orElseThrow().getAccessTokenVersion());
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + legacy)).andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.id").value(owner.getId())).andExpect(jsonPath("$.identity.email").doesNotExist());
        inactive(); reactivate("owner", "Correct123!").andExpect(status().isNoContent());
        mvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + legacy)).andExpect(status().isUnauthorized());
    }

    // Falhas indistinguíveis não alteram estado, não enviam mensagens e não aceitam tokens como credenciais.
    @Test void invalidCredentialsAndSecurityBoundary() throws Exception {
        inactive();
        mvc.perform(post("/api/auth/reactivation").header("X-Sonarfy-CSRF", "1").contentType("application/json")
                .content("{\"login\":\"other\",\"password\":\"Correct123!\",\"userId\":" + owner.getId() + "}"))
                .andExpect(status().isNoContent());
        for (String login : new String[]{"missing", "owner"}) reactivate(login, "Wrong123!").andExpect(status().isUnauthorized());
        reactivate("owner", "é".repeat(37)).andExpect(status().isUnauthorized());
        db.update("update users set verified = false where user_id = ?", owner.getId());
        reactivate("owner", "Correct123!").andExpect(status().isUnauthorized());
        for (String token : new String[]{"invalid", jwt.generateRegistrationToken(owner.getId().toString()),
                jwt.generatePasswordResetToken(owner.getId().toString()), jwt.generateAccessToken(owner.getId().toString())}) {
            mvc.perform(post("/api/auth/reactivation").header("X-Sonarfy-CSRF", "1").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/reactivation").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/reactivation").header("X-Sonarfy-CSRF", "1").header("Origin", "https://evil.example")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/reactivation")).andExpect(status().isUnauthorized());
        assertFalse(users.findById(owner.getId()).orElseThrow().isActive()); assertEquals(0, notices()); verifyNoInteractions(email);
    }

    // Rollback restaura a inatividade e as sessões, sem deixar aviso disponível ao remetente.
    @Test void rollbackAndUncommittedMail() {
        refresh.issue(owner); inactive();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.reactivate(new br.com.pablohcarmo.sonarfy.dto.LoginDto("owner", "Correct123!"));
            assertFalse(mails.dispatchOne()); status.setRollbackOnly();
        });
        assertFalse(users.findById(owner.getId()).orElseThrow().isActive()); assertEquals(0, notices());
        assertEquals(1, db.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, owner.getId()));
        verifyNoInteractions(email);
    }

    // Falha SMTP não desfaz a reativação; retentativa e retomada de reserva abandonada são persistidas.
    @Test void deliveryRetryAndLeaseRecovery() throws Exception {
        inactive(); reactivate("owner", "Correct123!").andExpect(status().isNoContent());
        when(email.sendEmail(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("simulated"));
        assertTrue(mails.dispatchOne()); assertTrue(users.findById(owner.getId()).orElseThrow().isActive());
        assertFalse(mails.dispatchOne());
        db.update("update reactivation_mail_outbox set available_at = now(), lease_id = ?, lease_until = now() - interval '1 second'", java.util.UUID.randomUUID());
        reset(email); assertTrue(mails.dispatchOne()); assertFalse(mails.dispatchOne());
        verify(email).sendEmail(eq("owner@example.com"), eq("Conta reativada - Sonarfy"), contains("reativada"));
    }

    // Reativações concorrentes geram uma transição e um aviso; a relação removida não reaparece.
    @Test void concurrentReactivationAndRemovedRelationship() throws Exception {
        following.change(owner.getId(), other.getId(), true); following.change(owner.getId(), other.getId(), false); inactive();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<Void> call = () -> { start.await(); reactivate("owner", "Correct123!").andExpect(status().isNoContent()); return null; };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, notices()); assertEquals(1, users.findById(owner.getId()).orElseThrow().getAccessTokenVersion());
        assertEquals(0, db.queryForObject("select count(*) from user_following", Integer.class));
    }

    // Refresh e relacionamento aguardam a transição; após o commit só a relação elegível pode prosseguir.
    @Test void serializesWithRefreshAndFollowing() throws Exception {
        String token = refresh.issue(owner); inactive();
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var transition = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                service.reactivate(new br.com.pablohcarmo.sonarfy.dto.LoginDto("owner", "Correct123!"));
                ready.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            }));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                var rotation = pool.submit(() -> assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> refresh.rotate(token)));
                var relation = pool.submit(() -> following.change(other.getId(), owner.getId(), true));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (db.queryForObject("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Integer.class) < 2) {
                    assertTrue(System.nanoTime() < deadline, "As operações devem aguardar o bloqueio da conta"); Thread.sleep(20);
                }
                release.countDown(); transition.get(5, TimeUnit.SECONDS); relation.get(5, TimeUnit.SECONDS); rotation.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertEquals(1, notices()); assertEquals(1, db.queryForObject("select count(*) from user_following", Integer.class));
    }

    // A reserva é persistida antes do envio e impede dois remetentes de consumir o mesmo evento.
    @Test void concurrentDispatchDoesNotSendSameLeaseTwice() throws Exception {
        inactive(); reactivate("owner", "Correct123!");
        var sending = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(email.sendEmail(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            sending.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return "sent";
        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> mails.dispatchOne());
            try { assertTrue(sending.await(5, TimeUnit.SECONDS)); assertFalse(mails.dispatchOne()); }
            finally { release.countDown(); }
            assertTrue(first.get(5, TimeUnit.SECONDS));
        }
        verify(email, times(1)).sendEmail(anyString(), anyString(), anyString());
    }

    // Uma troca de senha que confirma antes da reativação impede o uso da senha antiga.
    @Test void passwordIsRecheckedAfterWaitingForAccountLock() throws Exception {
        inactive(); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var change = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                db.update("update users set password_hash = ? where user_id = ?", passwords.encode("NewPassword123!"), owner.getId());
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var attempt = pool.submit(() -> reactivate("owner", "Correct123!").andExpect(status().isUnauthorized()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (db.queryForObject("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Integer.class) < 1) {
                    assertTrue(System.nanoTime() < deadline); Thread.sleep(20);
                }
                release.countDown(); change.get(5, TimeUnit.SECONDS); attempt.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertFalse(users.findById(owner.getId()).orElseThrow().isActive()); assertEquals(0, notices());
    }

    // Um login pausado depois de validar a senha antiga não pode emitir refresh após a reativação.
    @Test void loginStartedBeforeReactivationCannotCreateNewSession() throws Exception {
        var checked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            Object valid = call.callRealMethod();
            if (first.getAndSet(false)) { checked.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
            return valid;
        }).when(passwords).matches(eq("Correct123!"), anyString());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var login = pool.submit(() -> mvc.perform(post("/api/auth/login").header("X-Sonarfy-CSRF", "1")
                    .contentType("application/json").content("{\"login\":\"owner\",\"password\":\"Correct123!\"}"))
                    .andExpect(status().isUnauthorized()));
            try {
                assertTrue(checked.await(5, TimeUnit.SECONDS)); inactive();
                reactivate("owner", "Correct123!").andExpect(status().isNoContent());
            } finally { release.countDown(); }
            login.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, db.queryForObject("select count(*) from refresh_tokens", Integer.class));
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.ReviewController;
import br.com.pablohcarmo.sonarfy.controllers.EchoesController;
import br.com.pablohcarmo.sonarfy.dto.ReviewInputDto;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Aceita somente um banco local dedicado, com schema previamente importado e Hibernate validate.
@EnabledIfSystemProperty(named = "sonarfy.test.reviews-postgres", matches = "true")
@SpringJUnitWebConfig(ReviewPostgresTest.Config.class)
class ReviewPostgresTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, ReviewController.class, ReviewService.class,
            EchoesController.class, EchoQueryService.class, EchoCursorCodec.class, CollectionCursorCodec.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean EchoSyncService echoSyncService() { return org.mockito.Mockito.mock(EchoSyncService.class); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String url = System.getenv().getOrDefault("SONARFY_REVIEWS_TEST_JDBC_URL", "");
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/sonarfy_reviews_validation"))
            throw new IllegalArgumentException("Dedicated local reviews validation database required.");
        r.add("sonarfy.test.jdbc-url", () -> url);
        r.add("sonarfy.test.db-user", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_USER", "postgres"));
        r.add("sonarfy.test.db-password", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_PASSWORD", ""));
        r.add("api.security.token.secret", () -> "reviews-isolated-test-secret");
        r.add("app.frontend-url", () -> "http://localhost:3000");
        r.add("spring.mail.username", () -> "noreply@example.com");
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate db;
    @Autowired ReviewService reviews;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    long actor, other;
    String album = "/api/albums/local-album/reviews";
    ReviewInputDto input = new JsonMapper().readValue("{\"rating\":8.5,\"reviewText\":\"  Ótimo  \"}", ReviewInputDto.class);

    @BeforeEach void prepare() {
        db.update("DELETE FROM listens"); db.update("DELETE FROM tracks");
        db.update("DELETE FROM reviews"); db.update("DELETE FROM albums"); db.update("DELETE FROM artists");
        db.update("DELETE FROM users");
        actor = account("reviewer"); other = account("other");
        db.update("INSERT INTO artists (spotify_id, name) VALUES ('artist', 'Artist')");
        db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, 'local-album', 'Album' FROM artists");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // Ecos deriva o estado da própria avaliação, inclusive nota zero, sem afetar contagens ou cursores.
    @Test void echoesReflectOwnReviewLifecycleWithoutChangingPagination() throws Exception {
        db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, 'second-album', 'Second' FROM artists");
        db.update("INSERT INTO tracks (spotify_id, album_id, name) SELECT spotify_id, album_id, name FROM albums");
        for (long owner : new long[]{actor, other}) db.update("""
                INSERT INTO listens (user_id, album_id, track_id, spotify_track_id, played_at)
                SELECT ?, album_id, track_id, spotify_id, '2026-11-23T00:00:00Z' FROM tracks
                """, owner);
        db.update("""
                INSERT INTO listens (user_id, album_id, track_id, spotify_track_id, played_at)
                SELECT ?, album_id, track_id, spotify_id, '2026-11-22T00:00:00Z' FROM tracks WHERE spotify_id = 'local-album'
                """, actor);
        String route = "/api/echoes/albums";
        var first = mvc.perform(get(route).param("limit", "1").header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].album.spotifyId").value("second-album"))
                .andExpect(jsonPath("$.items[0].rated").value(false)).andExpect(jsonPath("$.items[0].reviewId").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = new JsonMapper().readTree(first).get("nextCursor").asText();
        long otherId = reviews.create(other, "local-album", input).reviewId();
        mvc.perform(get(route).param("cursor", cursor).header("Authorization", token(actor)))
                .andExpect(jsonPath("$.items[0].rated").value(false)).andExpect(jsonPath("$.items[0].reviewId").isEmpty());
        mvc.perform(post(album).header("Authorization", token(actor)).contentType("application/json").content("{\"rating\":0}"))
                .andExpect(status().isCreated());
        long id = reviews.current(actor, "local-album").reviewId();
        mvc.perform(get(route).param("cursor", cursor).header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].album.spotifyId").value("local-album"))
                .andExpect(jsonPath("$.items[0].rated").value(true)).andExpect(jsonPath("$.items[0].reviewId").value(id))
                .andExpect(jsonPath("$.items[0].listenCount").value(2)).andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(put("/api/reviews/" + id).header("Authorization", token(actor)).contentType("application/json").content("{\"rating\":10}"))
                .andExpect(status().isOk());
        mvc.perform(get(route).header("Authorization", token(actor)))
                .andExpect(jsonPath("$.items[0].rated").value(false)).andExpect(jsonPath("$.items[1].reviewId").value(id))
                .andExpect(jsonPath("$.items[1].rated").value(true));
        mvc.perform(delete("/api/reviews/" + id).header("Authorization", token(actor))).andExpect(status().isNoContent());
        mvc.perform(get(route).param("cursor", cursor).header("Authorization", token(actor)))
                .andExpect(jsonPath("$.items[0].rated").value(false)).andExpect(jsonPath("$.items[0].reviewId").isEmpty())
                .andExpect(jsonPath("$.items[0].listenCount").value(2));
        mvc.perform(get(route).header("Authorization", token(other)))
                .andExpect(jsonPath("$.items[1].rated").value(true)).andExpect(jsonPath("$.items[1].reviewId").value(otherId));
    }

    // O ciclo HTTP persiste normalização, identidade, substituição e exclusão definitiva.
    @Test void lifecycleAndOwnership() throws Exception {
        mvc.perform(get(album + "/me").header("Authorization", token(actor))).andExpect(status().isNotFound());
        mvc.perform(post(album).header("Authorization", token(actor)).contentType("application/json")
                .content("{\"rating\":8.5,\"reviewText\":\"  Ótimo  \",\"userId\":" + other + "}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", album + "/me"))
                .andExpect(jsonPath("$.rating").value(8.5)).andExpect(jsonPath("$.reviewText").value("Ótimo"))
                .andExpect(jsonPath("$.album.spotifyId").value("local-album")).andExpect(jsonPath("$.email").doesNotExist());
        long id = db.queryForObject("SELECT review_id FROM reviews", Long.class);
        Object created = db.queryForObject("SELECT created_at FROM reviews", Object.class);
        mvc.perform(post(album).header("Authorization", token(actor)).contentType("application/json").content("{\"rating\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(get(album + "/me").header("Authorization", token(other))).andExpect(status().isNotFound());
        for (var request : List.of(put("/api/reviews/" + id).contentType("application/json").content("{\"rating\":10}"),
                delete("/api/reviews/" + id)))
            mvc.perform(request.header("Authorization", token(other))).andExpect(status().isNotFound());
        mvc.perform(put("/api/reviews/" + id).header("Authorization", token(actor)).contentType("application/json").content("{\"rating\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewText").isEmpty()).andExpect(jsonPath("$.rating").value(0));
        assertEquals(created, db.queryForObject("SELECT created_at FROM reviews", Object.class));
        mvc.perform(get(album + "/me").header("Authorization", token(actor))).andExpect(status().isOk());
        mvc.perform(delete("/api/reviews/" + id).header("Authorization", token(actor))).andExpect(status().isNoContent());
        mvc.perform(get(album + "/me").header("Authorization", token(actor))).andExpect(status().isNotFound());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM reviews", Integer.class));
    }

    // Valida precisão antes do NUMERIC, comprimento por code point e tipos JSON sem coerção.
    @Test void validationAndUnknownAlbum() throws Exception {
        for (String body : List.of("{}", "{\"rating\":null}", "{\"rating\":-0.5}", "{\"rating\":10.5}",
                "{\"rating\":8.25}", "{\"rating\":8.4999999999999999999999}", "{\"rating\":\"8\"}", "{\"rating\":8,\"reviewText\":3}",
                "{\"rating\":8,\"reviewText\":\"" + "a".repeat(256) + "\"}")) {
            mvc.perform(post(album).header("Authorization", token(actor)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
            mvc.perform(put("/api/reviews/1").header("Authorization", token(actor)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/albums/unknown/reviews").header("Authorization", token(actor)).contentType("application/json").content("{\"rating\":10}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(album).header("Authorization", token(actor)).contentType("application/json")
                .content("{\"rating\":10,\"reviewText\":\"" + "🎵".repeat(255) + "\"}")) .andExpect(status().isCreated());
        assertEquals(255, db.queryForObject("SELECT char_length(review_text) FROM reviews", Integer.class));
    }

    // Filtro Bearer real rejeita token inválido, ausente e contas inativas ou não verificadas em todas as rotas.
    @Test void authentication() throws Exception {
        for (String route : List.of("GET " + album + "/me", "GET /api/reviews/me",
                "GET /api/profile/by-handle/other/reviews", "GET /api/profile/by-handle/other/collection", "POST " + album,
                "PUT /api/reviews/1", "DELETE /api/reviews/1")) {
            var parts = route.split(" ");
            var method = org.springframework.http.HttpMethod.valueOf(parts[0]);
            mvc.perform(request(method, parts[1])).andExpect(status().isUnauthorized());
            for (String invalid : List.of("Bearer invalid", "Bearer " + jwt.generateRegistrationToken(Long.toString(actor))))
                mvc.perform(request(method, parts[1]).header("Authorization", invalid)).andExpect(status().isUnauthorized());
            for (String column : List.of("active", "verified")) {
                db.update("UPDATE users SET " + column + " = false WHERE user_id = ?", actor);
                mvc.perform(request(method, parts[1]).header("Authorization", token(actor))).andExpect(status().isUnauthorized());
                db.update("UPDATE users SET " + column + " = true WHERE user_id = ?", actor);
            }
        }
    }

    // Empates de datas não repetem nem omitem itens; cursor e consulta ficam restritos ao autor.
    @Test void paginationAndIsolation() throws Exception {
        mvc.perform(get("/api/reviews/me").header("Authorization", token(actor))).andExpect(jsonPath("$.items").isEmpty());
        for (int i = 0; i < 5; i++) {
            db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, ?, 'Album' FROM artists", "album" + i);
            reviews.create(actor, "album" + i, input);
        }
        reviews.create(other, "local-album", input);
        db.update("UPDATE reviews SET created_at = '2026-01-01T00:00:00Z'");
        Set<Long> seen = new HashSet<>(); Long cursor = null;
        do {
            var page = reviews.list(actor, 2, cursor);
            for (var item : page.items()) assertTrue(seen.add(item.reviewId()));
            cursor = page.nextBeforeId();
        } while (cursor != null);
        assertEquals(5, seen.size());
        mvc.perform(get("/api/reviews/me?limit=2").header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextBeforeId").isNumber()).andExpect(header().string("Cache-Control", "no-store"));
        for (String query : List.of("limit=0", "limit=51", "beforeId=0", "beforeId=abc"))
            mvc.perform(get("/api/reviews/me?" + query).header("Authorization", token(actor))).andExpect(status().isBadRequest());
    }

    // Concorrência resulta em um sucesso e conflitos controlados, com constraint independente do serviço.
    @Test void concurrentCreationAndDatabaseConstraint() throws Exception {
        var pool = Executors.newFixedThreadPool(8); var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post(album).header("Authorization", token(actor)).contentType("application/json").content("{\"rating\":8.5}"))
                        .andReturn().getResponse().getStatus();
            }));
            start.countDown(); List<Integer> statuses = new ArrayList<>();
            for (var future : futures) statuses.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(statuses, 201)); assertEquals(7, Collections.frequency(statuses, 409));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update(
                    "INSERT INTO reviews (user_id, album_id, rating) SELECT user_id, album_id, rating FROM reviews"));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("UPDATE reviews SET rating = 8.2"));
        } finally { pool.shutdownNow(); }
    }

    // Rollback externo restaura a avaliação; elegibilidade é relida pelo serviço mesmo com principal antigo.
    @Test void rollbackAndStaleEligibility() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            reviews.create(actor, "local-album", input); tx.setRollbackOnly();
        });
        assertEquals(0, db.queryForObject("SELECT count(*) FROM reviews", Integer.class));
        db.update("UPDATE users SET active = false WHERE user_id = ?", actor);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> reviews.create(actor, "local-album", input));
    }

    // A desativação em andamento força todas as mutações a aguardar e reler o estado confirmado.
    @Test void waitsForDeactivation() throws Exception {
        long id = reviews.create(actor, "local-album", input).reviewId();
        for (Runnable mutation : List.<Runnable>of(() -> reviews.create(actor, "local-album", input),
                () -> reviews.replace(actor, id, input), () -> reviews.delete(actor, id))) {
            var pool = Executors.newSingleThreadExecutor();
            try {
                Future<?>[] pending = new Future<?>[1];
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    db.update("UPDATE users SET active = false WHERE user_id = ?", actor);
                    pending[0] = pool.submit(mutation);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    int waiting;
                    do {
                        db.execute("SELECT pg_stat_clear_snapshot()");
                        waiting = db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
                    } while (waiting == 0 && System.nanoTime() < deadline);
                    assertTrue(waiting > 0); assertFalse(pending[0].isDone());
                });
                var error = assertThrows(ExecutionException.class, () -> pending[0].get(10, TimeUnit.SECONDS));
                assertEquals(401, assertInstanceOf(org.springframework.web.server.ResponseStatusException.class, error.getCause()).getStatusCode().value());
                db.update("UPDATE users SET active = true WHERE user_id = ?", actor);
                assertEquals(1, db.queryForObject("SELECT count(*) FROM reviews", Integer.class));
            } finally { pool.shutdownNow(); }
        }
    }

    // Perfil vazio difere de perfil indisponível; reativar preserva as avaliações publicadas.
    @Test void profileVisibilityAndSafeAuthor() throws Exception {
        String route = "/api/profile/by-handle/OTHER/reviews";
        mvc.perform(get(route).header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextBeforeId").isEmpty());
        long id = reviews.create(other, "local-album", input).reviewId();
        mvc.perform(get(route).header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.author.id").value(other)).andExpect(jsonPath("$.author.handle").value("other"))
                .andExpect(jsonPath("$.author.displayName").value("other"))
                .andExpect(jsonPath("$.author.avatarUrl").isEmpty()).andExpect(jsonPath("$.author.length()").value(4))
                .andExpect(jsonPath("$.items[0].reviewId").value(id))
                .andExpect(jsonPath("$.items[0].album.artist.name").value("Artist"))
                .andExpect(jsonPath("$.items[0].rating").value(8.5))
                .andExpect(jsonPath("$.items[0].email").doesNotExist());
        for (String column : List.of("active", "verified")) {
            db.update("UPDATE users SET " + column + " = false WHERE user_id = ?", other);
            mvc.perform(get(route).header("Authorization", token(actor))).andExpect(status().isNotFound());
            db.update("UPDATE users SET " + column + " = true WHERE user_id = ?", other);
            mvc.perform(get(route).header("Authorization", token(actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].reviewId").value(id));
        }
        mvc.perform(get("/api/profile/by-handle/missing/reviews").header("Authorization", token(actor)))
                .andExpect(status().isNotFound());
        db.update("UPDATE users SET handle = 'renamed' WHERE user_id = ?", other);
        mvc.perform(get(route).header("Authorization", token(actor))).andExpect(status().isNotFound());
        mvc.perform(get("/api/profile/by-handle/renamed/reviews").header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.author.id").value(other));
    }

    // Fronteiras numéricas nunca mudam o autor; edições e empates não deslocam as páginas.
    @Test void profilePaginationIsolationAndMutation() throws Exception {
        var own = reviews.create(actor, "local-album", input);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, ?, 'Album' FROM artists", "profile" + i);
            ids.add(reviews.create(other, "profile" + i, input).reviewId());
        }
        db.update("UPDATE reviews SET created_at = '2026-01-01T00:00:00Z'");
        Set<Long> seen = new HashSet<>(); Long cursor = null;
        do {
            var page = reviews.profile(actor, "other", 2, cursor);
            for (var item : page.items()) assertTrue(seen.add(item.reviewId()));
            cursor = page.nextBeforeId();
        } while (cursor != null);
        assertEquals(new HashSet<>(ids), seen);
        var first = reviews.profile(actor, "other", 2, null);
        reviews.replace(other, ids.getFirst(), new JsonMapper().readValue("{\"rating\":0}", ReviewInputDto.class));
        var second = reviews.profile(actor, "other", 2, first.nextBeforeId());
        assertEquals(List.of(ids.get(2), ids.get(1)), second.items().stream().map(r -> r.reviewId()).toList());
        mvc.perform(get("/api/profile/by-handle/reviewer/reviews").param("beforeId", first.nextBeforeId().toString())
                        .header("Authorization", token(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reviewId").value(own.reviewId()));
        mvc.perform(delete("/api/reviews/" + ids.getFirst()).header("Authorization", token(actor)))
                .andExpect(status().isNotFound());
        reviews.delete(other, ids.getFirst());
        assertEquals(4, reviews.profile(actor, "other", 50, null).items().size());
        assertEquals(1, reviews.profile(actor, "reviewer", 50, null).items().size());
    }

    // A rota valida limites e tipos antes de executar consultas sem limite.
    @Test void profilePaginationValidation() throws Exception {
        for (String query : List.of("limit=0", "limit=51", "limit=x", "beforeId=0", "beforeId=-1",
                "beforeId=abc", "beforeId=9223372036854775808"))
            mvc.perform(get("/api/profile/by-handle/other/reviews?" + query).header("Authorization", token(actor)))
                    .andExpect(status().isBadRequest());
        mvc.perform(get("/api/profile/by-handle/other/reviews?beforeId=1").header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    private String token(long id) { return "Bearer " + jwt.generateAccessToken(Long.toString(id)); }

    // Todas as ordens são globais, com extremos, empates e filtro independente da paginação.
    @Test void collectionOrdersCountsAndContexts() throws Exception {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 6; i++) {
            db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, ?, 'Album' FROM artists", "c" + i);
            ids.add(reviews.create(other, "c" + i, new JsonMapper().readValue(
                    "{\"rating\":" + List.of(0, 10, 5, 5, 10, 0).get(i)
                            + ",\"reviewText\":" + (i % 2 == 0 ? "\"Texto\"" : "null") + "}", ReviewInputDto.class)).reviewId());
        }
        reviews.create(actor, "local-album", input);
        db.update("UPDATE reviews SET created_at = '2026-01-01T00:00:00Z'");
        var sorts = List.of("recent", "oldest", "highest", "lowest");
        var orders = List.of(List.of(5,4,3,2,1,0), List.of(0,1,2,3,4,5), List.of(4,1,3,2,5,0), List.of(0,5,2,3,1,4));
        for (int s = 0; s < sorts.size(); s++) for (boolean written : List.of(false, true)) {
            var seen = new ArrayList<Long>(); String cursor = null;
            do {
                var page = reviews.collection(actor, "OTHER", 2, sorts.get(s), Boolean.toString(written), cursor);
                assertEquals(6, page.totalReviews()); assertEquals(written ? 3 : 6, page.matchingReviews());
                page.items().forEach(r -> seen.add(r.reviewId())); cursor = page.nextCursor();
            } while (cursor != null);
            assertEquals(orders.get(s).stream().filter(i -> !written || i % 2 == 0).map(ids::get).toList(), seen);
        }
        var first = reviews.collection(actor, "other", 1, "highest", "false", null);
        String cursor = first.nextCursor();
        for (String query : List.of("sort=lowest", "writtenOnly=true"))
            mvc.perform(get("/api/profile/by-handle/other/collection?" + query).param("cursor", cursor)
                    .header("Authorization", token(actor))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/profile/by-handle/reviewer/collection?sort=highest").param("cursor", cursor)
                .header("Authorization", token(actor))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/profile/by-handle/other/collection?sort=highest").param("cursor", cursor)
                .header("Authorization", token(other))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/profile/by-handle/other/collection").header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.author.length()").value(4)).andExpect(jsonPath("$.items[0].length()").value(6))
                .andExpect(jsonPath("$.items[0].album.artist.name").value("Artist"))
                .andExpect(jsonPath("$.totalReviews").value(6)).andExpect(jsonPath("$.matchingReviews").value(6));
        for (String query : List.of("sort=written", "sort=RECENT", "limit=0", "limit=51", "limit=x",
                "writtenOnly=1", "writtenOnly=TRUE", "cursor=", "cursor=invalid", "cursor=" + cursor.substring(1)))
            mvc.perform(get("/api/profile/by-handle/other/collection?" + query).header("Authorization", token(actor)))
                    .andExpect(status().isBadRequest());
    }

    // Exclusão da fronteira não invalida o cursor; mudanças de nota podem omitir ou repetir itens.
    @Test void collectionChangesBetweenPages() throws Exception {
        var low = reviews.create(other, "local-album", new JsonMapper().readValue("{\"rating\":0}", ReviewInputDto.class));
        db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, 'new', 'New' FROM artists");
        var high = reviews.create(other, "new", input);
        var first = reviews.collection(actor, "other", 1, "highest", "false", null);
        reviews.replace(other, low.reviewId(), new JsonMapper().readValue("{\"rating\":10}", ReviewInputDto.class));
        assertTrue(reviews.collection(actor, "other", 1, "highest", "false", first.nextCursor()).items().isEmpty());
        reviews.replace(other, high.reviewId(), new JsonMapper().readValue("{\"rating\":0}", ReviewInputDto.class));
        assertEquals(high.reviewId(), reviews.collection(actor, "other", 1, "highest", "false", first.nextCursor()).items().getFirst().reviewId());
        first = reviews.collection(actor, "other", 1, "recent", "false", null);
        reviews.delete(other, high.reviewId());
        reviews.create(other, "new", input);
        var next = reviews.collection(actor, "other", 50, "recent", "false", first.nextCursor());
        assertEquals(List.of(low.reviewId()), next.items().stream().map(r -> r.reviewId()).toList());
        assertEquals(2, next.totalReviews());
    }

    // Estado vazio, autoria própria e ocultação de perfis usam as mesmas regras das avaliações.
    @Test void collectionVisibility() throws Exception {
        mvc.perform(get("/api/profile/by-handle/reviewer/collection").header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalReviews").value(0))
                .andExpect(jsonPath("$.matchingReviews").value(0)).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        reviews.create(actor, "local-album", input);
        assertEquals(1, reviews.collection(actor, "reviewer", 20, "recent", "true", null).totalReviews());
        for (String column : List.of("active", "verified")) {
            db.update("UPDATE users SET " + column + " = false WHERE user_id = ?", other);
            mvc.perform(get("/api/profile/by-handle/other/collection").header("Authorization", token(actor))).andExpect(status().isNotFound());
            db.update("UPDATE users SET " + column + " = true WHERE user_id = ?", other);
        }
        mvc.perform(get("/api/profile/by-handle/missing/collection").header("Authorization", token(actor))).andExpect(status().isNotFound());
    }

    // Bloqueia a leitura de álbuns após iniciar o snapshot e confirma uma desativação concorrente.
    @Test void collectionSnapshotDuringDeactivation() throws Exception {
        reviews.create(other, "local-album", input);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = new ArrayList<Future<br.com.pablohcarmo.sonarfy.dto.ReviewDto.CollectionPage>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                db.execute("LOCK TABLE albums IN ACCESS EXCLUSIVE MODE");
                pending.add(pool.submit(() -> reviews.collection(actor, "other", 20, "recent", "false", null)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); int waiting;
                do {
                    db.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
                } while (waiting == 0 && System.nanoTime() < deadline);
                assertTrue(waiting > 0);
                db.update("UPDATE users SET active = false, display_name = 'Changed' WHERE user_id = ?", other);
                db.update("DELETE FROM reviews WHERE user_id = ?", other);
            });
            var page = pending.getFirst().get(10, TimeUnit.SECONDS);
            assertEquals("other", page.author().displayName()); assertEquals(1, page.items().size());
            assertEquals(1, page.totalReviews()); assertEquals(1, page.matchingReviews());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> reviews.collection(actor, "other", 20, "recent", "false", null));
        } finally { pool.shutdownNow(); }
    }
    private long account(String handle) {
        return db.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash,
                                   city, country, birth_date, active, verified)
                SELECT permission_id, 'Private', 'Name', ?, ?, ?, 'private-hash', 'City', 'Country', '2000-01-01', true, true
                FROM permissions WHERE permission = 'ROLE_USER' RETURNING user_id
                """, Long.class, handle, handle, handle + "@example.com");
    }
}

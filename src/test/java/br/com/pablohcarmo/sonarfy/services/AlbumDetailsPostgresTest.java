package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.*;
import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.AlbumController;
import br.com.pablohcarmo.sonarfy.controllers.ReviewController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.*;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

// Servidor HTTP real, segurança Bearer real, banco dedicado e dependências Spotify simuladas.
@EnabledIfSystemProperty(named = "sonarfy.test.catalog-postgres", matches = "true")
@SpringBootTest(classes = AlbumDetailsPostgresTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=optional:classpath:/album-test.properties",
                "spring.main.allow-bean-definition-overriding=true", "server.address=127.0.0.1"})
class AlbumDetailsPostgresTest {
    static final String ID = "4aawyAB9vmqN3uQ7FjRGTy";
    static final JsonMapper JSON = new JsonMapper();
    @Configuration @EnableAutoConfiguration
    @EnableFeignClients(clients = {SpotifyApiClient.class, SpotifyTokenClient.class})
    @Import({SecurityConfig.class, AlbumController.class, AlbumSearchService.class, AlbumDetailsService.class, AlbumCatalogStore.class,
            AlbumCatalogService.class, SpotifyAlbumService.class, SpotifyCatalogTokenService.class, AuthSpotifyService.class,
            ReviewController.class, ReviewService.class, CollectionCursorCodec.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String url = System.getenv().getOrDefault("SONARFY_CATALOG_TEST_JDBC_URL", "");
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/sonarfy_catalog_validation"))
            throw new IllegalArgumentException("Dedicated local catalog validation database required.");
        r.add("sonarfy.test.jdbc-url", () -> url);
        r.add("sonarfy.test.db-user", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_USER", "postgres"));
        r.add("sonarfy.test.db-password", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_PASSWORD", ""));
        r.add("spotify.api-url", AlbumDetailsPostgresTest::provider);
        r.add("spotify.auth-url", AlbumDetailsPostgresTest::provider);
        r.add("SPOTIFY_CLIENT_ID", () -> "catalog-client"); r.add("SPOTIFY_CLIENT_SECRET", () -> "catalog-secret");
        r.add("SPOTIFY_REDIRECT_URI", () -> "http://127.0.0.1:8080/api/spotify/callback");
        r.add("api.security.token.secret", () -> "album-details-isolated-secret");
        r.add("app.frontend-url", () -> "http://localhost:3000"); r.add("spring.mail.username", () -> "test@example.com");
    }
    static String provider() { return System.getProperty("sonarfy.test.spotify-api-url", "http://127.0.0.1:55442/spotify-api"); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired JwtService jwt;
    @Autowired SpotifyCatalogTokenService tokens;
    @Autowired ReviewService reviews;
    @Autowired CollectionCursorCodec cursors;
    long actor;
    @BeforeEach void prepare() throws Exception {
        db.execute("TRUNCATE users, artists CASCADE");
        actor = user("actor", true, true);
        tokens.invalidate("token-ok");
        control("reset");
    }

    // Busca nao persiste; selecao e publicacao reutilizam as rotas existentes sem conexao pessoal.
    @Test void searchesThenResolvesAndPublishes() throws Exception {
        assertEquals(401, get("search?q=Daft%20Punk", false).statusCode());
        assertEquals(200, get("search?q=Daft%20Punk", true).statusCode());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM albums", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM spotify_connection", Integer.class));
        assertEquals(200, get(ID, true).statusCode());
        assertEquals(404, get(ID + "/reviews/me", true).statusCode());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/albums/" + ID + "/reviews"))
                .header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(actor)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"rating\":8.5}" )).build();
        assertEquals(201, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(409, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, get(ID + "/reviews/me", true).statusCode());
        assertEquals(1, control("ok").get("albumRequests").asInt());
    }

    // Média inclui zero e avaliações sem texto; edição, exclusão e elegibilidade refletem a próxima leitura.
    @Test void readsEligibleAggregatesAndEnforcesAuthentication() throws Exception {
        var first = get(ID, true);
        assertEquals(200, first.statusCode()); assertEquals(0, JSON.readTree(first.body()).get("reviewCount").asInt());
        assertTrue(JSON.readTree(first.body()).get("averageRating").isNull());
        for (long owner : new long[]{actor, user("other", true, true), user("inactive", false, true), user("unverified", true, false)})
            db.update("INSERT INTO reviews (user_id, album_id, rating) SELECT ?, album_id, ? FROM albums WHERE spotify_id = ?",
                    owner, owner == actor ? 0 : 10, ID);
        db.execute("DROP INDEX idx_reviews_album_review_desc");
        for (int i = 0; i < 2; i++) db.execute(Files.readString(Path.of("db/migrations/018_add_album_reviews_index.sql")));
        var rated = get(ID, true);
        assertEquals("no-store", rated.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(2, JSON.readTree(rated.body()).get("reviewCount").asInt());
        assertEquals(5, JSON.readTree(rated.body()).get("averageRating").asInt());
        assertFalse(rated.body().contains("email")); assertFalse(rated.body().contains("album_id"));
        db.update("UPDATE reviews SET rating = 8 WHERE user_id = ?", actor);
        assertEquals(9, JSON.readTree(get(ID, true).body()).get("averageRating").asInt());
        db.update("DELETE FROM reviews WHERE user_id = ?", actor);
        assertEquals(1, JSON.readTree(get(ID, true).body()).get("reviewCount").asInt());
        assertEquals(1, control("ok").get("albumRequests").asInt());
        assertEquals(1, control("ok").get("tokensIssued").asInt());
        assertEquals(401, get(ID, false).statusCode()); assertEquals(400, get("invalid", true).statusCode());
        db.update("UPDATE users SET active = false WHERE user_id = ?", actor);
        assertEquals(401, get(ID, true).statusCode());
        db.update("UPDATE users SET active = true, verified = false WHERE user_id = ?", actor);
        assertEquals(401, get(ID, true).statusCode());
    }

    // Falhas do provedor não viram 401 Sonarfy; 429 preserva o prazo e 401 descarta o token da aplicação.
    @Test void mapsProviderFailuresAndReacquiresRejectedApplicationToken() throws Exception {
        for (int status : new int[]{401, 403, 404, 429, 503}) {
            var response = get(ID.substring(0, 19) + status, true);
            assertEquals(status == 401 || status == 403 ? 502 : status == 429 ? 503 : status, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.body().isEmpty());
            if (status == 429) assertEquals("900", response.headers().firstValue("Retry-After").orElseThrow());
        }
        assertEquals(2, control("ok").get("tokensIssued").asInt());
        for (String mode : new String[]{"401", "503", "invalid"}) {
            tokens.invalidate("token-ok"); control(mode);
            assertEquals(mode.equals("503") ? 503 : 502, get(ID, true).statusCode());
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM albums", Integer.class));
    }

    // A coleção versionada também chama o servidor Sonarfy real, sem publicar dados no Postman.
    @Test @EnabledIfSystemProperty(named = "sonarfy.test.albums-postman", matches = "true")
    void runsPostmanCollection() throws Exception {
        var root = JSON.readTree(Files.readString(Path.of("docs/api/sonarfy-albums.postman_collection.json")));
        for (var variable : root.get("variable")) {
            if (variable.get("key").asText().equals("baseUrl")) ((ObjectNode) variable).put("value", "http://127.0.0.1:" + port);
            if (variable.get("key").asText().equals("accessToken")) ((ObjectNode) variable).put("value", jwt.generateAccessToken(Long.toString(actor)));
        }
        var runtime = Files.createTempFile("sonarfy-albums-", ".json");
        try {
            Files.writeString(runtime, JSON.writeValueAsString(root));
            var command = new java.util.ArrayList<String>();
            if (System.getProperty("os.name").startsWith("Windows")) command.addAll(java.util.List.of("cmd.exe", "/c"));
            command.addAll(java.util.List.of("postman", "collection", "run", runtime.toString(), "--no-report-events"));
            for (String scenario : new String[]{"empty", "community"}) {
                if (scenario.equals("community")) seedCommunity();
                var log = Path.of("target/albums-postman-" + scenario + ".log");
                var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Postman timed out"); }
                assertEquals(0, process.exitValue(), "See " + log);
            }
        } finally { Files.deleteIfExists(runtime); }
    }

    // Todas as ordenações são globais, com desempate e paginação sem duplicatas para notas iguais.
    @Test void paginatesCommunityWithFourSortOrdersAndPublicAuthors() throws Exception {
        long[] ids = seedCommunity();
        db.execute("DROP INDEX idx_reviews_album_rating_review_desc");
        for (int i = 0; i < 2; i++) db.execute(Files.readString(Path.of("db/migrations/019_add_album_review_rating_index.sql")));
        String[] sorts = {"recent", "oldest", "highest", "lowest"};
        long[][] expected = {{ids[3],ids[2],ids[1],ids[0]}, {ids[0],ids[1],ids[2],ids[3]},
                {ids[1],ids[2],ids[0],ids[3]}, {ids[3],ids[0],ids[2],ids[1]}};
        for (int i = 0; i < sorts.length; i++) {
            var found = new java.util.ArrayList<Long>();
            String cursor = null;
            do {
                var response = get(ID + "/reviews?limit=2&sort=" + sorts[i] + (cursor == null ? "" : "&cursor=" + cursor), true);
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                assertFalse(response.body().contains("email")); assertFalse(response.body().contains("password"));
                var page = JSON.readTree(response.body());
                for (var item : page.get("items")) found.add(item.get("reviewId").asLong());
                cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            } while (cursor != null);
            assertArrayEquals(expected[i], found.stream().mapToLong(Long::longValue).toArray());
        }
        var recent = JSON.readTree(get(ID + "/reviews", true).body());
        assertEquals(ids[3], recent.get("items").get(0).get("reviewId").asLong());
        assertTrue(recent.get("items").get(0).get("reviewText").isNull());
        assertEquals(0, recent.get("items").get(0).get("rating").asInt());
        assertEquals(4, JSON.readTree(get(ID, true).body()).get("reviewCount").asInt());
        assertEquals(6.75, JSON.readTree(get(ID, true).body()).get("averageRating").asDouble());
        assertEquals(0, control("ok").get("albumRequests").asInt());
        assertEquals(0, control("ok").get("tokensIssued").asInt());
    }

    // Cursor não é permissão e não pode atravessar álbum, conta, ordenação ou domínio de consulta.
    @Test void bindsCursorContextAndKeepsBoundaryAfterDeletion() throws Exception {
        long[] ids = seedCommunity();
        String cursor = JSON.readTree(get(ID + "/reviews?limit=2&sort=highest", true).body()).get("nextCursor").asText();
        assertEquals(400, get(ID + "/reviews?sort=lowest&cursor=" + cursor, true).statusCode());
        String otherAlbum = "0000000000000000000000";
        db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, ?, 'Other' FROM artists", otherAlbum);
        assertEquals(400, get(otherAlbum + "/reviews?sort=highest&cursor=" + cursor, true).statusCode());
        long original = actor;
        actor = user("visitor", true, true);
        assertEquals(400, get(ID + "/reviews?sort=highest&cursor=" + cursor, true).statusCode());
        actor = original;
        String profileCursor = cursors.encode(actor + ":" + actor + ":recent:false", new CollectionCursorCodec.Position(ids[0],17));
        assertEquals(400, get(ID + "/reviews?cursor=" + profileCursor, true).statusCode());
        long owner = db.queryForObject("SELECT user_id FROM reviews WHERE review_id = ?", Long.class, ids[2]);
        reviews.delete(owner, ids[2]);
        var next = JSON.readTree(get(ID + "/reviews?sort=highest&cursor=" + cursor, true).body());
        assertEquals(ids[0], next.get("items").get(0).get("reviewId").asLong());
        assertEquals(ids[3], next.get("items").get(1).get("reviewId").asLong());
        assertTrue(next.get("nextCursor").isNull());
        reviews.replace(actor, ids[0], JSON.readValue("{\"rating\":9}", br.com.pablohcarmo.sonarfy.dto.ReviewInputDto.class));
        assertEquals(ids[3], JSON.readTree(get(ID + "/reviews", true).body()).get("items").get(0).get("reviewId").asLong());
        var denied = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/reviews/" + ids[1]))
                .header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(actor))).DELETE().build();
        assertEquals(404, HttpClient.newHttpClient().send(denied, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // Vazio não é álbum desconhecido; desativação oculta publicações e reativação restaura a visibilidade.
    @Test void handlesEmptyIneligibleAuthorsAndInvalidQueries() throws Exception {
        long[] ids = seedCommunity();
        for (String query : new String[]{"limit=0", "limit=51", "limit=abc", "sort=rating", "cursor=bad", "cursor="})
            assertEquals(400, get(ID + "/reviews?" + query, true).statusCode(), query);
        assertEquals(401, get(ID + "/reviews", false).statusCode());
        assertEquals(400, get("bad/reviews", true).statusCode());
        assertEquals(404, get("0000000000000000000000/reviews", true).statusCode());
        db.update("UPDATE users SET active = false WHERE user_id <> ?", actor);
        reviews.delete(actor, ids[0]);
        assertEquals(0, JSON.readTree(get(ID + "/reviews", true).body()).get("items").size());
        db.update("UPDATE users SET active = true WHERE handle = 'zero'");
        assertEquals(1, JSON.readTree(get(ID + "/reviews", true).body()).get("items").size());
        db.update("UPDATE users SET verified = false WHERE user_id = ?", actor);
        assertEquals(401, get(ID + "/reviews", true).statusCode());
    }

    long[] seedCommunity() {
        db.update("INSERT INTO artists (spotify_id, name) VALUES ('artist1', 'Artist One') ON CONFLICT DO NOTHING");
        db.update("INSERT INTO albums (artist_id, spotify_id, name, total_tracks) SELECT artist_id, ?, 'Album One', 70 FROM artists ON CONFLICT DO NOTHING", ID);
        long[] owners = {actor, user("highest",true,true), user("tie",true,true), user("zero",true,true),
                user("hidden",false,true), user("unverified",true,false)};
        double[] ratings = {8.5,10,8.5,0,10,10};
        long[] ids = new long[owners.length];
        for (int i = 0; i < owners.length; i++) ids[i] = db.queryForObject("""
                INSERT INTO reviews (user_id, album_id, rating, review_text)
                SELECT ?, album_id, ?, ? FROM albums WHERE spotify_id = ? RETURNING review_id
                """, Long.class, owners[i], ratings[i], i == 0 || i == 3 ? null : "Written review", ID);
        return ids;
    }

    long user(String handle, boolean active, boolean verified) {
        return db.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash,
                    city, country, birth_date, active, verified) SELECT permission_id, 'A', 'B', 'A B', ?, ?, 'hash',
                    'City', 'Country', '2000-01-01', ?, ? FROM permissions WHERE permission = 'ROLE_USER' RETURNING user_id
                """, Long.class, handle, handle + "@example.com", active, verified);
    }
    HttpResponse<String> get(String id, boolean authorized) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/albums/" + id));
        if (authorized) request.header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(actor)));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    tools.jackson.databind.JsonNode control(String mode) throws Exception {
        return JSON.readTree(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(provider() + "/__catalog/" + mode)).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }
}

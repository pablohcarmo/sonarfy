package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.mockito.Mockito;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.client.SpotifyTokenClient;
import br.com.pablohcarmo.sonarfy.config.CorsConfig;
import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.EchoesController;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Cadeia real Security + Controller + EchoSyncService + PostgreSQL + simulação Postman.
@EnabledIfSystemProperty(named = "sonarfy.test.echoes-sync-e2e", matches = "true")
@SpringJUnitWebConfig(EchoSyncPostgresSimulationTest.Config.class)
class EchoSyncPostgresSimulationTest {

    @Configuration
    @EnableWebMvc
    @EnableFeignClients(clients = {SpotifyApiClient.class, SpotifyTokenClient.class})
    @ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @Import({SecurityConfig.class, CorsConfig.class,
            SpotifyConnectionService.class, SpotifyTokenEncryptionService.class,
            AuthSpotifyService.class, SpotifyAccessTokenService.class,
            SpotifyRecentlyPlayedService.class, EchoImportWriter.class,
            EchoSyncService.class, EchoesController.class})
    static class Config extends EchoesPersistencePostgresTest.Config {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("sonarfy.echoes.test.jdbc-url",
                System.getenv().getOrDefault("SONARFY_ECHOES_TEST_JDBC_URL", ""));
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/sonarfy_echoes_validation")) {
            throw new IllegalArgumentException("A dedicated local echoes validation database is required.");
        }
        registry.add("sonarfy.test.jdbc-url", () -> url);
        registry.add("sonarfy.test.db-user", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_USER", "postgres"));
        registry.add("sonarfy.test.db-password", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_PASSWORD", ""));
        registry.add("sonarfy.test.schema-mode", () -> "validate");
        registry.add("spotify.api-url", () -> System.getProperty(
                "sonarfy.test.spotify-api-url", "http://127.0.0.1:55442/spotify-api"));
        registry.add("app.frontend-url", () -> "http://localhost:3000");
        registry.add("api.security.token.secret", () -> "echoes-sync-e2e-secret");
        registry.add("spring.mail.username", () -> "noreply@example.com");
        registry.add("SPOTIFY_CLIENT_ID", () -> "test-client");
        registry.add("SPOTIFY_CLIENT_SECRET", () -> "test-secret");
        registry.add("SPOTIFY_REDIRECT_URI", () -> "http://127.0.0.1/callback");
        registry.add("spotify_token_encryption_key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired WebApplicationContext context;
    @Autowired SpotifyConnectionService connections;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private long userOneId;
    private long userTwoId;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM Listens");
        jdbc.update("DELETE FROM Track_Artists");
        jdbc.update("DELETE FROM Tracks");
        jdbc.update("DELETE FROM Albums");
        jdbc.update("DELETE FROM Artists");
        jdbc.update("DELETE FROM users WHERE email IN ('sync1@example.com', 'sync2@example.com')");
        userOneId = insertUser("syncone", "sync1@example.com");
        userTwoId = insertUser("synctwo", "sync2@example.com");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private long insertUser(String handle, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email,
                    password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Sync', 'User', 'Sync User', ?, ?,
                    'test-hash', 'Brasilia', 'Brasil', '2000-01-01', true) RETURNING user_id
                """, Long.class, handle, email);
    }

    @Test
    void syncsEndToEndWithIsolationCooldownAndNoSecretLeaks() throws Exception {
        var rootLogger = (Logger) LoggerFactory.getLogger("br.com.pablohcarmo.sonarfy");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        rootLogger.addAppender(appender);
        try {
            connections.connect(userOneId, 0L, new SpotifyTokenResponseDto(
                    "token-ok", "Bearer", 3600L, "secret-refresh-token", "user-read-recently-played"));

            var response = mvc.perform(post("/api/echoes/sync")
                            .header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(userOneId))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.imported").value(1))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertFalse(response.contains("token-ok"));
            assertFalse(response.contains("secret-refresh-token"));

            // Segunda chamada imediata ativa cooldown sem duplicar escutas; usuário 2 permanece isolado.
            mvc.perform(post("/api/echoes/sync")
                            .header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(userOneId))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));

            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userTwoId));

            // Usuário 2 sem conexão retorna 409 sem afetar o usuário 1.
            mvc.perform(post("/api/echoes/sync")
                            .header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(userTwoId))))
                    .andExpect(status().isConflict());

            for (ILoggingEvent event : appender.list) {
                String msg = event.getFormattedMessage();
                assertFalse(msg.contains("token-ok"));
                assertFalse(msg.contains("secret-refresh-token"));
            }
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    @Autowired UserRepository users;
    @Autowired SpotifyAccessTokenService access;
    @Autowired EchoImportWriter writer;
    @Autowired PlatformTransactionManager manager;

    // O rate limit da segunda página preserva o lote e bloqueia novas consultas pelo prazo completo.
    @Test
    void partialRateLimitPreservesListensAndWaitThroughRealHttpChain() throws Exception {
        connections.connect(userOneId, 0L, new SpotifyTokenResponseDto(
                "token-partial-429", "Bearer", 3600L, "refresh-fixture", "user-read-recently-played"));
        String bearer = "Bearer " + jwt.generateAccessToken(Long.toString(userOneId));
        mvc.perform(post("/api/echoes/sync").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.imported").value(50));
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
        assertTrue(jdbc.queryForObject("SELECT next_allowed_at >= last_succeeded_at + INTERVAL '900 seconds' FROM echo_sync_state WHERE user_id = ?",
                Boolean.class, userOneId));
        // Simula o avanço de 61 segundos sem aguardar ou alterar o relógio da máquina.
        jdbc.update("UPDATE echo_sync_state SET next_allowed_at = next_allowed_at - INTERVAL '61 seconds' WHERE user_id = ?", userOneId);
        var blocked = mvc.perform(post("/api/echoes/sync").header("Authorization", bearer))
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertTrue(Long.parseLong(blocked.getHeader("Retry-After")) > 60L);
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
    }

    private EchoSyncService orchestrator(SpotifyRecentlyPlayedService api) {
        connections.connect(userOneId, 0L, new SpotifyTokenResponseDto(
                "token-ok", "Bearer", 3600L, "refresh-fixture", "user-read-recently-played"));
        return new EchoSyncService(users, access, api, writer, jdbc, manager);
    }

    // A tentativa seguinte relê páginas mesmo quando a primeira já foi importada parcialmente.
    @Test
    void recoversMissingPageAfterPartialImport() {
        var api = Mockito.mock(SpotifyRecentlyPlayedService.class);
        var service = orchestrator(api);
        var album = new SpotifyRecentlyPlayedResponseDto.AlbumObject(
                "album1", "Album", "2024", "year", List.of(),
                List.of(new SpotifyRecentlyPlayedResponseDto.ArtistObject("artist1", "Artist")));
        var track = new SpotifyRecentlyPlayedResponseDto.TrackObject(
                "track1", "Track", 1000, false, false, "track", album, album.artists());
        var newest = OffsetDateTime.parse("2026-11-22T00:00:00Z");
        var page = IntStream.range(0, 50).mapToObj(n ->
                new SpotifyRecentlyPlayedResponseDto.PlayHistoryItem(track, newest.minusSeconds(n))).toList();
        var first = new SpotifyRecentlyPlayedResponseDto(page,
                new SpotifyRecentlyPlayedResponseDto.Cursors(null, "1000"), 50);
        var second = new SpotifyRecentlyPlayedResponseDto(List.of(
                new SpotifyRecentlyPlayedResponseDto.PlayHistoryItem(track, newest.minusSeconds(60))), null, 50);
        Mockito.when(api.fetchPage("token-ok", 50, null)).thenReturn(first);
        Mockito.when(api.fetchPage("token-ok", 50, 1000L))
                .thenThrow(new SpotifyApiException(
                        SpotifyApiException.Category.UNAVAILABLE)).thenReturn(second);
        assertFalse(service.sync(userOneId).body().complete());
        jdbc.update("UPDATE echo_sync_state SET next_allowed_at = NULL WHERE user_id = ?", userOneId);
        var recovered = service.sync(userOneId).body();
        assertTrue(recovered.complete());
        assertEquals(1, recovered.imported());
        assertEquals(51, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
    }

    // Uma execução vencida não grava nem libera a reserva adquirida por outra execução.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void expiredWorkerCannotWriteOrReleaseReplacementLease(boolean replaced) throws Exception {
        var api = Mockito.mock(SpotifyRecentlyPlayedService.class);
        var service = orchestrator(api);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        Mockito.when(api.fetchPage("token-ok", 50, null)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return new SpotifyRecentlyPlayedResponseDto(List.of(), null, 50);
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var task = pool.submit(() -> service.sync(userOneId));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var replacement = replaced ? UUID.randomUUID() : jdbc.queryForObject(
                    "SELECT lease_id FROM echo_sync_state WHERE user_id = ?", UUID.class, userOneId);
            jdbc.update("UPDATE echo_sync_state SET lease_id = ?, lease_until = CURRENT_TIMESTAMP + INTERVAL '90 seconds' WHERE user_id = ?",
                    replacement, userOneId);
            if (!replaced) jdbc.update("UPDATE echo_sync_state SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE user_id = ?", userOneId);
            finish.countDown();
            var failure = assertThrows(ExecutionException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(HttpStatus.CONFLICT,
                    ((ResponseStatusException) failure.getCause()).getStatusCode());
            assertEquals(replaced ? replacement : null, jdbc.queryForObject("SELECT lease_id FROM echo_sync_state WHERE user_id = ?", UUID.class, userOneId));
            assertNull(jdbc.queryForObject("SELECT last_succeeded_at FROM echo_sync_state WHERE user_id = ?", OffsetDateTime.class, userOneId));
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    // Uma rejeição remota anterior à reativação não apaga as credenciais preservadas.
    @Test
    void staleRejectionCannotInvalidateCurrentAccountEpoch() {
        orchestrator(Mockito.mock(SpotifyRecentlyPlayedService.class));
        var captured = access.getValidCredential(userOneId, false).orElseThrow();
        jdbc.update("UPDATE users SET spotify_authorization_version = spotify_authorization_version + 1 WHERE user_id = ?", userOneId);
        access.markReauthorizationRequired(userOneId, captured);
        assertEquals("CONNECTED", jdbc.queryForObject("SELECT status FROM spotify_connection WHERE user_id = ?", String.class, userOneId));
        var fresh = access.getValidCredential(userOneId, false).orElseThrow();
        access.markReauthorizationRequired(userOneId, fresh);
        assertEquals("REAUTHORIZATION_REQUIRED", jdbc.queryForObject("SELECT status FROM spotify_connection WHERE user_id = ?", String.class, userOneId));
    }
}

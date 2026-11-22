package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyTokenClient;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.enums.SpotifyConnectionStatus;
import br.com.pablohcarmo.sonarfy.repositories.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.SpotifyController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

// Serviço transacional e cliente Feign reais; somente o provedor HTTP é simulado.
@SpringJUnitWebConfig(SpotifyAccessTokenServiceTest.Config.class)
@TestPropertySource(properties = {"app.frontend-url=http://localhost:3000", "api.security.token.secret=test",
        "spring.mail.username=noreply@example.com", "SPOTIFY_CLIENT_ID=test", "SPOTIFY_CLIENT_SECRET=test",
        "SPOTIFY_REDIRECT_URI=http://127.0.0.1/callback", "SPOTIFY_FRONTEND_REDIRECT_URI=http://localhost:3000/",
        "spotify_token_encryption_key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spotify_token_encryption_keyring=rotated=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "spotify_token_encryption_active_key_id=rotated"})
class SpotifyAccessTokenServiceTest {
    @Configuration
    @EnableWebMvc
    @EnableFeignClients(clients = SpotifyTokenClient.class)
    @ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @Import({SpotifyAccessTokenService.class, SpotifyConnectionService.class, AuthSpotifyService.class,
            SpotifyTokenEncryptionService.class, SpotifyTokenKeyRotationService.class,
            SecurityConfig.class,
            SpotifyController.class,
            SpotifyAuthorizationService.class, SpotifyAuthorizationStateService.class, SpotifyCallbackService.class})
    static class Config extends EmailReservationIntegrationTest.Config {}

    static HttpServer server;
    static volatile String body;
    static volatile int status;
    static volatile CountDownLatch arrived, release;
    static final AtomicInteger calls = new AtomicInteger();
    static final String SUCCESS = "{\"access_token\":\"new-access\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    @Autowired SpotifyAccessTokenService access;
    @Autowired SpotifyConnectionService service;
    @Autowired SpotifyConnectionRepository connections;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired SpotifyTokenEncryptionService encryption;
    @Autowired SpotifyTokenKeyRotationService rotation;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @Autowired JwtService jwt;
    MockMvc mvc;
    Long id;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/token", exchange -> {
            calls.incrementAndGet();
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(form.contains("grant_type=refresh_token"));
            assertTrue(form.contains("refresh_token=old-refresh"));
            assertEquals("Basic dGVzdDp0ZXN0", exchange.getRequestHeaders().getFirst("Authorization"));
            arrived.countDown();
            try { release.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try (exchange) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException ignored) { }
        });
        server.start();
        registry.add("spotify.auth-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        boolean postgres = Boolean.getBoolean("sonarfy.test.spotify-postgres");
        registry.add("sonarfy.test.jdbc-url", () -> postgres
                ? "jdbc:postgresql://127.0.0.1:" + Integer.getInteger("sonarfy.test.spotify-port", 55447) + "/sonarfy_spotify_refresh_test"
                : "jdbc:h2:mem:spotify_refresh;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("sonarfy.test.db-user", () -> postgres ? "spotify_test" : "sa");
        registry.add("sonarfy.test.db-password", () -> postgres ? "isolated-test-password" : "");
    }

    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build();
        calls.set(0); status = 200; body = SUCCESS;
        arrived = new CountDownLatch(1); release = new CountDownLatch(0);
        var user = new User();
        user.setHandle(UUID.randomUUID().toString().substring(0, 12));
        user.setEmail(user.getHandle() + "@example.com"); user.setDisplayName("Spotify test");
        user.setActive(true); user.setVerified(true);
        user.setPermission(permissions.findByName("ROLE_USER").orElseGet(() -> {
            var role = new br.com.pablohcarmo.sonarfy.entities.Permission();
            role.setName("ROLE_USER"); return permissions.saveAndFlush(role);
        }));
        id = users.saveAndFlush(user).getId();
        service.connect(id, 0, new SpotifyTokenResponseDto("old-access", "Bearer", 1L, "old-refresh", "scope"));
    }

    @AfterAll static void stop() { server.stop(0); }

    // Preserva refresh token e escopos omitidos, e reutiliza o acesso recém-renovado.
    @Test void refreshesOnceAndPreservesOptionalFields() {
        long generationBeforeRefresh = connections.findByUserId(id).orElseThrow().getAuthorizationGeneration();
        assertEquals("new-access", access.getValidAccessToken(id).orElseThrow());
        assertEquals("new-access", access.getValidAccessToken(id).orElseThrow());
        assertEquals(1, calls.get());
        var row = connections.findByUserId(id).orElseThrow();
        assertEquals("old-refresh", encryption.decrypt(row.getRefreshTokenEncrypted(), id, "refresh"));
        assertEquals("scope", row.getScopes());
        assertEquals(generationBeforeRefresh, row.getAuthorizationGeneration());
    }

    @Test void rotatesRefreshAndRetainsConnectionDate() {
        var date = service.getConnection(id).connectedAt();
        body = SUCCESS.replace("3600}", "3600,\"refresh_token\":\"rotated\",\"scope\":\"changed\"}");
        access.getValidAccessToken(id);
        var row = connections.findByUserId(id).orElseThrow();
        assertEquals("rotated", encryption.decrypt(row.getRefreshTokenEncrypted(), id, "refresh"));
        assertEquals("changed", row.getScopes()); assertEquals(date.toInstant(), row.getConnectedAt().toInstant());
    }

    @Test void commitsRevocationAndAllowsReauthorizationAndDisconnect() throws Exception {
        status = 400; body = "{\"error\":\"invalid_grant\"}";
        assertTrue(access.getValidAccessToken(id).isEmpty());
        assertEquals(SpotifyConnectionStatus.REAUTHORIZATION_REQUIRED, service.getConnection(id).status());
        var revokedRow = connections.findByUserId(id).orElseThrow();
        assertNull(revokedRow.getRefreshTokenEncrypted());
        assertEquals(2L, revokedRow.getAuthorizationGeneration());
        mvc.perform(get("/api/spotify/connection")
                .header("Authorization", "Bearer " + jwt.generateAccessToken(id.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("REAUTHORIZATION_REQUIRED"))
                .andExpect(jsonPath("$.recentListensAuthorized").value(false))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertTrue(access.getValidAccessToken(id).isEmpty()); assertEquals(1, calls.get());
        assertEquals(0, rotation.reencryptNextBatch(10));
        service.connect(id, 0, new SpotifyTokenResponseDto("restored", "Bearer", 3600L, "refresh", null));
        assertEquals(3L, connections.findByUserId(id).orElseThrow().getAuthorizationGeneration());
        assertEquals("restored", access.getValidAccessToken(id).orElseThrow());
        service.disconnect(id); assertTrue(access.getValidAccessToken(id).isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void httpFailuresPreserveCredentials(int responseStatus) {
        status = responseStatus; body = responseStatus == 400
                ? "{\"error\":\"invalid_client\"}" : "{\"error\":\"invalid_grant\"}";
        var before = connections.findByUserId(id).orElseThrow().getRefreshTokenEncrypted();
        assertThrows(RuntimeException.class, () -> access.getValidAccessToken(id));
        assertEquals(before, connections.findByUserId(id).orElseThrow().getRefreshTokenEncrypted());
        assertEquals(SpotifyConnectionStatus.CONNECTED, service.getConnection(id).status());
        assertEquals(1, calls.get());
    }

    // Um corpo ilegível ou um campo diferente não comprova revogação.
    @ParameterizedTest @ValueSource(strings = {"not-json", "null", "{\"error_description\":\"invalid_grant\"}"})
    void unrecognizedRejectionPreservesCredentials(String response) {
        status = 400; body = response;
        assertThrows(SpotifyIntegrationException.class, () -> access.getValidAccessToken(id));
        assertEquals(SpotifyConnectionStatus.CONNECTED, service.getConnection(id).status());
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "not-json",
            "{\"access_token\":\"x\",\"token_type\":\"Bearer\",\"expires_in\":0}",
            "{\"access_token\":\"x\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"\"}"})
    void invalidResponsesDoNotOverwriteTokens(String response) {
        body = response;
        assertThrows(RuntimeException.class, () -> access.getValidAccessToken(id));
        assertEquals("old-access", encryption.decrypt(connections.findByUserId(id).orElseThrow()
                .getAccessTokenEncrypted(), id, "access"));
    }

    // A segunda renovação espera o banco e reutiliza o resultado da primeira.
    @Test void serializesRefreshesAndDoesNotUndoDisconnect() throws Exception {
        release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first = pool.submit(() -> access.getValidAccessToken(id));
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            var second = pool.submit(() -> access.getValidAccessToken(id));
            var deletion = pool.submit(() -> service.disconnect(id));
            assertThrows(TimeoutException.class, () -> deletion.get(150, TimeUnit.MILLISECONDS));
            release.countDown(); first.get(8, TimeUnit.SECONDS); second.get(8, TimeUnit.SECONDS);
            deletion.get(8, TimeUnit.SECONDS);
            assertEquals(1, calls.get()); assertTrue(connections.findByUserId(id).isEmpty());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    // A transação do consumidor não pode desfazer uma rotação já aceita pelo provedor.
    @Test void consumerRollbackDoesNotUndoRenewalOrRevocation() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            access.getValidAccessToken(id); tx.setRollbackOnly();
        });
        assertEquals("new-access", access.getValidAccessToken(id).orElseThrow());
        expire(); status = 400; body = "{\"error\":\"invalid_grant\"}";
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertTrue(access.getValidAccessToken(id).isEmpty()); tx.setRollbackOnly();
        });
        assertEquals(SpotifyConnectionStatus.REAUTHORIZATION_REQUIRED, service.getConnection(id).status());
    }

    @Test void validTokenAvoidsHttpButSafetyMarginAndForceRefreshCallProvider() {
        service.connect(id, 0, new SpotifyTokenResponseDto("valid", "Bearer", 3600L, "old-refresh", "user-read-recently-played"));
        var cached = access.getValidCredential(id, false).orElseThrow();
        assertEquals("valid", cached.accessToken());
        assertEquals("user-read-recently-played", cached.scopes());
        assertEquals(0, calls.get());
        var forced = access.getValidCredential(id, true).orElseThrow();
        assertEquals("new-access", forced.accessToken());
        assertEquals(cached.connectionId(), forced.connectionId());
        assertEquals(cached.authorizationGeneration(), forced.authorizationGeneration());
        assertEquals(users.findById(id).orElseThrow().getSpotifyAuthorizationVersion(), cached.spotifyAuthorizationVersion());
        assertEquals(cached.spotifyAuthorizationVersion(), forced.spotifyAuthorizationVersion());
        assertEquals(1, calls.get());
        service.connect(id, 0, new SpotifyTokenResponseDto("near-expiry", "Bearer", 60L, "old-refresh", null));
        access.getValidAccessToken(id); assertEquals(2, calls.get());
    }

    @Test void serializesTwoRefreshesWithoutDisconnect() throws Exception {
        release = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> access.getValidAccessToken(id));
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            var second = pool.submit(() -> access.getValidAccessToken(id));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void keyRotationCannotOverwriteRenewedTokens() throws Exception {
        var legacy = new SpotifyTokenEncryptionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var row = connections.findByUserId(id).orElseThrow();
            row.reencryptTokens(legacy.encrypt("old-access", id, "access"),
                    legacy.encrypt("old-refresh", id, "refresh"));
        });
        release = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> access.getValidAccessToken(id));
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            var second = pool.submit(() -> rotation.reencryptNextBatch(100));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            release.countDown(); first.get(8, TimeUnit.SECONDS); second.get(8, TimeUnit.SECONDS);
            assertEquals("new-access", access.getValidAccessToken(id).orElseThrow());
            assertEquals(1, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private void expire() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var row = connections.findByUserId(id).orElseThrow();
            row.updateTokens(row.getAccessTokenEncrypted(), row.getRefreshTokenEncrypted(), row.getTokenType(),
                    row.getScopes(), OffsetDateTime.now().minusSeconds(1), OffsetDateTime.now());
        });
    }

    @Test void timeoutDoesNotRetryOrDiscardTokens() {
        release = new CountDownLatch(1);
        try {
            var failure = assertThrows(SpotifyIntegrationException.class,
                    () -> access.getValidAccessToken(id));
            assertEquals(SpotifyIntegrationException.Category.TIMEOUT, failure.getCategory());
            assertEquals(1, calls.get());
            assertEquals(SpotifyConnectionStatus.CONNECTED, service.getConnection(id).status());
        } finally { release.countDown(); }
    }
}

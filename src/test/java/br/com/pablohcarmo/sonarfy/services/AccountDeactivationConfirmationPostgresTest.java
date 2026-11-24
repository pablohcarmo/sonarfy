package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Confirmação interna real; a rota de prova existe só no teste para exercitar a cadeia Bearer.
@EnabledIfSystemProperty(named = "sonarfy.test.deactivation-postgres", matches = "true")
@SpringJUnitWebConfig(AccountDeactivationConfirmationPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=deactivation-confirmation-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class AccountDeactivationConfirmationPostgresTest {
    @Configuration @EnableWebMvc
    @Import({AccountDeactivationConfirmationService.class, AuthService.class, SecurityConfig.class, Probe.class})
    static class Config extends AccountDeactivationRequestPostgresTest.Config {}
    @RestController static class Probe {
        @GetMapping("/api/deactivation-test/probe") String authenticated() { return "ok"; }
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate db;
    @MockitoSpyBean UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired RefreshTokenService refresh;
    @Autowired JwtService jwt;
    @Autowired AuthService auth;
    @Autowired AccountReactivationService reactivation;
    @Autowired AccountDeactivationRequestService request;
    @Autowired AccountDeactivationConfirmationService confirmation;
    @Autowired AccountDeactivationRequestRepository requests;
    @Autowired AccountDeactivationMailRepository mails;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager entities;
    long actor, other;
    String token, session, foreignSession;
    MockMvc mvc;
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    @BeforeEach void fixture() {
        reset(users); users.deleteAll(); actor = account("confirmation-a"); other = account("confirmation-b");
        session = refresh.issue(users.findById(actor).orElseThrow()); foreignSession = refresh.issue(users.findById(other).orElseThrow());
        request.request(actor, "Current123!"); token = mails.claim().orElseThrow().authorization().token();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup() { reset(users); users.deleteAll(); }
    long account(String handle) {
        return db.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Conta', 'Teste', 'Conta Teste', ?, ?, ?, 'Campinas', 'Brasil', '1990-01-01', true) RETURNING user_id
                """, Long.class, handle, handle + "@example.com", passwords.encode("Current123!"));
    }
    Map<String, String> preserved() {
        var result = new TreeMap<String, String>();
        for (String table : List.of("user_following", "spotify_connection", "reviews", "listens", "albums", "tracks", "artists"))
            result.put(table, db.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]') FROM " + table + " t", String.class));
        return result;
    }

    // Preserva dados e relações, revoga históricos/sucessores e impede ressurreição de tokens após reativação.
    @Test void fullTransitionAndExplicitReactivationPreserveDataAndRejectOldCredentials() throws Exception {
        db.update("INSERT INTO user_following (follower_id, following_id) VALUES (?, ?), (?, ?)", actor, other, other, actor);
        db.update("INSERT INTO spotify_connection (user_id, access_token_encrypted, refresh_token_encrypted, token_type, access_token_expires_at) VALUES (?, 'cipher-a', 'cipher-r', 'Bearer', statement_timestamp() + interval '1 hour')", actor);
        long artist = db.queryForObject("INSERT INTO artists (spotify_id, name) VALUES ('confirmation-artist', 'Artist') ON CONFLICT (spotify_id) DO UPDATE SET name = EXCLUDED.name RETURNING artist_id", Long.class);
        long album = db.queryForObject("INSERT INTO albums (artist_id, spotify_id, name) VALUES (?, 'confirmation-album', 'Album') ON CONFLICT (spotify_id) DO UPDATE SET name = EXCLUDED.name RETURNING album_id", Long.class, artist);
        long track = db.queryForObject("INSERT INTO tracks (album_id, spotify_id, name) VALUES (?, 'confirmation-track', 'Track') ON CONFLICT (spotify_id) DO UPDATE SET name = EXCLUDED.name RETURNING track_id", Long.class, album);
        db.update("INSERT INTO reviews (user_id, album_id, rating, is_favorite) VALUES (?, ?, 9.5, true)", actor, album);
        db.update("INSERT INTO listens (user_id, album_id, track_id, spotify_track_id, played_at) VALUES (?, ?, ?, 'confirmation-track', statement_timestamp())", actor, album, track);
        var before = preserved(); var original = users.findById(actor).orElseThrow();
        String personalData = db.queryForObject("SELECT (to_jsonb(u) - ARRAY['active', 'access_token_version', 'spotify_authorization_version', 'updated_at'])::text FROM users u WHERE user_id = ?", String.class, actor);
        String oldAccess = jwt.generateAccessToken(Long.toString(actor), original.getAccessTokenVersion());
        var successor = refresh.rotate(session);
        mvc.perform(get("/api/deactivation-test/probe").header("Authorization", "Bearer " + oldAccess)).andExpect(status().isOk());
        confirmation.confirm(token);
        var inactive = users.findById(actor).orElseThrow();
        assertFalse(inactive.isActive()); assertTrue(inactive.isVerified()); assertEquals(before, preserved());
        assertEquals(original.getEmail(), inactive.getEmail()); assertEquals(original.getPassword(), inactive.getPassword());
        assertEquals(personalData, db.queryForObject("SELECT (to_jsonb(u) - ARRAY['active', 'access_token_version', 'spotify_authorization_version', 'updated_at'])::text FROM users u WHERE user_id = ?", String.class, actor));
        assertEquals(original.getAccessTokenVersion() + 1, inactive.getAccessTokenVersion());
        assertEquals(original.getSpotifyAuthorizationVersion() + 1, inactive.getSpotifyAuthorizationVersion());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, actor));
        assertThrows(ResponseStatusException.class, () -> refresh.rotate(successor.refreshToken()));
        assertThrows(ResponseStatusException.class, () -> auth.login(new LoginDto("confirmation-a", "Current123!")));
        mvc.perform(get("/api/deactivation-test/probe").header("Authorization", "Bearer " + oldAccess)).andExpect(status().isUnauthorized());
        db.update("UPDATE account_deactivation_mail_outbox SET lease_until = statement_timestamp()");
        assertTrue(mails.claim().isEmpty());
        assertNotNull(refresh.rotate(foreignSession));
        reactivation.reactivate(new LoginDto("confirmation-a", "Current123!"));
        mvc.perform(get("/api/deactivation-test/probe").header("Authorization", "Bearer " + oldAccess)).andExpect(status().isUnauthorized());
        var login = auth.login(new LoginDto("confirmation-a", "Current123!"));
        assertThrows(ResponseStatusException.class, () -> refresh.issue(original, false, original.getAccessTokenVersion()));
        mvc.perform(get("/api/deactivation-test/probe").header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(actor), login.accessTokenVersion()))).andExpect(status().isOk());
        assertThrows(ResponseStatusException.class, () -> confirmation.confirm(token));
        assertNotNull(refresh.rotate(login.refreshToken()));
        assertThrows(ResponseStatusException.class, () -> refresh.rotate(session));
    }

    // Limites da entrada são verificados antes do consumo, preservando o token válido.
    @Test void rejectsMissingAndOversizedTokensWithoutConsumingAuthorization() {
        for (String invalid : new String[]{null, "", "x".repeat(257)})
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> confirmation.confirm(invalid)).getStatusCode().value());
        confirmation.confirm(token); assertFalse(users.findById(actor).orElseThrow().isActive());
    }

    // Mesmo após flush real, uma falha restaura consumo, versões, estado e todas as sessões.
    @Test void failureAfterFlushRollsBackAllEffects() {
        tx().executeWithoutResult(status -> doAnswer(call -> {
            entities.flush();
            assertFalse(db.queryForObject("SELECT active FROM users WHERE user_id = ?", Boolean.class, actor));
            throw new IllegalStateException("test failure after flush");
        }).when(users).saveAndFlush(any(User.class)));
        assertThrows(IllegalStateException.class, () -> confirmation.confirm(token)); reset(users);
        var current = users.findById(actor).orElseThrow(); assertTrue(current.isActive());
        assertEquals(0, current.getAccessTokenVersion()); assertEquals(0, current.getSpotifyAuthorizationVersion());
        assertNotNull(refresh.rotate(session));
        confirmation.confirm(token); assertFalse(users.findById(actor).orElseThrow().isActive());
    }

    // Entradas recusadas não revogam sessões nem consomem autorizações de conta inelegível.
    @ParameterizedTest @ValueSource(strings = {"malformed", "expired", "future", "replaced", "inactive", "unverified"})
    void rejectsInvalidOrIneligibleAuthorization(String scenario) {
        String input = token;
        switch (scenario) {
            case "malformed" -> input = jwt.generatePasswordResetToken(Long.toString(actor));
            case "expired" -> db.update("UPDATE account_deactivation_requests SET issued_at = statement_timestamp() - interval '16 minutes', expires_at = statement_timestamp()");
            case "future" -> db.update("UPDATE account_deactivation_requests SET issued_at = statement_timestamp() + interval '1 minute', expires_at = statement_timestamp() + interval '16 minutes'");
            case "replaced" -> tx().executeWithoutResult(status -> requests.replace(actor));
            case "inactive" -> db.update("UPDATE users SET active = false WHERE user_id = ?", actor);
            case "unverified" -> db.update("UPDATE users SET verified = false WHERE user_id = ?", actor);
        }
        String rejected = input;
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> confirmation.confirm(rejected)).getStatusCode().value());
        assertEquals(0, users.findById(actor).orElseThrow().getAccessTokenVersion());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, actor));
        assertTrue(db.queryForObject("SELECT consumed_at IS NULL FROM account_deactivation_requests WHERE user_id = ?", Boolean.class, actor));
    }

    // Duas confirmações simultâneas têm um único vencedor, sem duplicar incrementos de versão.
    @Test void concurrentConfirmationHasOneWinner() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<Boolean> confirm = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { confirmation.confirm(token); return true; }
                catch (ResponseStatusException rejected) { assertEquals(400, rejected.getStatusCode().value()); return false; }
            };
            var first = pool.submit(confirm); var second = pool.submit(confirm); start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, users.findById(actor).orElseThrow().getAccessTokenVersion());
    }

    // Refresh que aguarda a confirmação não pode criar um sucessor depois da desativação.
    @Test void waitingRefreshCannotSurviveConfirmation() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = tx().execute(status -> {
                db.queryForObject("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, actor);
                var future = pool.submit(() -> assertThrows(ResponseStatusException.class, () -> refresh.rotate(session)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    db.execute("SELECT pg_stat_clear_snapshot()");
                    if (db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class) > 0) break;
                    assertTrue(System.nanoTime() < deadline);
                    try { Thread.sleep(20); } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                }
                confirmation.confirm(token); return future;
            });
            assertEquals(401, waiting.get(5, TimeUnit.SECONDS).getStatusCode().value());
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, actor));
    }
}

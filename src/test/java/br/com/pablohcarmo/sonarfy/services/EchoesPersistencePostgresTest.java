package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyAccessCredential;
import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.*;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.StaleSpotifyAuthorizationException;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import javax.sql.DataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

// Somente PostgreSQL descartável: valida persistência, deduplicação e bloqueio contra autorização antiga.
@EnabledIfSystemProperty(named = "sonarfy.test.echoes-postgres", matches = "true")
@SpringJUnitConfig(EchoesPersistencePostgresTest.Config.class)
class EchoesPersistencePostgresTest {

    @Configuration
    @Import({SpotifyConnectionService.class, SpotifyTokenEncryptionService.class,
            EchoImportWriter.class, AccountReactivationService.class, ReactivationMailService.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        // Valida o schema preparado pela migration sem recriar tabelas pelo Hibernate.
        @Bean @Override
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("br.com.pablohcarmo.sonarfy.entities");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    }

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
        registry.add("app.frontend-url", () -> "http://localhost:3000");
        registry.add("api.security.token.secret", () -> "echoes-test-secret");
        registry.add("spring.mail.username", () -> "noreply@example.com");
        registry.add("spotify_token_encryption_key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired EchoImportWriter writer;
    @Autowired SpotifyConnectionService connections;
    @Autowired SpotifyConnectionRepository connectionRepo;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountReactivationService reactivation;
    @Autowired PasswordEncoder passwords;

    private long userOneId;
    private long userTwoId;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM Listens");
        jdbc.update("DELETE FROM Track_Artists");
        jdbc.update("DELETE FROM Tracks");
        jdbc.update("DELETE FROM Albums");
        jdbc.update("DELETE FROM Artists");
        jdbc.update("DELETE FROM users WHERE email IN ('echoes1@example.com', 'echoes2@example.com')");
        userOneId = insertUser("echoes1", "echoes1@example.com");
        userTwoId = insertUser("echoes2", "echoes2@example.com");
    }

    private long insertUser(String handle, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email,
                    password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Echoes', 'Fixture', 'Echoes Fixture', ?, ?,
                    'test-hash', 'Brasilia', 'Brasil', '2000-01-01', true) RETURNING user_id
                """, Long.class, handle, email);
    }

    private SpotifyAccessCredential connect(long userId, String scope) {
        long version = users.findById(userId).orElseThrow().getSpotifyAuthorizationVersion();
        connections.connect(userId, version,
                new SpotifyTokenResponseDto("access-" + userId, "Bearer", 3600L, "refresh-" + userId, scope));
        var row = connectionRepo.findByUserId(userId).orElseThrow();
        return new SpotifyAccessCredential("access-" + userId, row.getId(),
                row.getAuthorizationGeneration(), version, row.getScopes());
    }

    private List<PlayHistoryItem> sampleItems() {
        var album = new AlbumObject("alb1", "Shared Album", "2023-08-15", "day",
                List.of(new ImageObject("https://i.scdn.co/image/cover1", 300, 300)),
                List.of(new ArtistObject("art1", "Artist One")));
        var valid1 = new PlayHistoryItem(
                new TrackObject("trk1", "Track One", 200000, false, false, "track", album,
                        List.of(new ArtistObject("art1", "Artist One"), new ArtistObject("art2", "Artist Two"))),
                OffsetDateTime.parse("2026-11-21T20:00:00Z"));
        var valid2 = new PlayHistoryItem(
                new TrackObject("trk2", "Track Two", 180000, true, false, "track", album,
                        List.of(new ArtistObject("art2", "Artist Two"))),
                OffsetDateTime.parse("2026-11-21T19:50:00Z"));
        var local = new PlayHistoryItem(
                new TrackObject(null, "Local Track", 120000, false, true, "track", album, List.of()),
                OffsetDateTime.parse("2026-11-21T19:40:00Z"));
        return List.of(valid1, valid2, local);
    }

    @Test
    void writesIdempotentlyAndDeduplicatesOnRepeat() {
        var cred = connect(userOneId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        var t1 = OffsetDateTime.parse("2026-11-21T21:00:00Z");
        var first = writer.writeBatch(userOneId, cred, sampleItems(), t1);

        assertEquals(2, first.imported());
        assertEquals(0, first.alreadyKnown());
        assertEquals(1, first.skipped());
        assertEquals(OffsetDateTime.parse("2026-11-21T20:00:00Z").toInstant(), first.newestPlayedAt().toInstant());

        var second = writer.writeBatch(userOneId, cred, sampleItems(), t1.plusMinutes(5));
        assertEquals(0, second.imported());
        assertEquals(2, second.alreadyKnown());
        assertEquals(1, second.skipped());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
    }

    @Test
    void rejectsStaleConnectionIdAfterDisconnectAndReconnectEvenAtSameGeneration() {
        var oldCred = connect(userOneId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        assertEquals(1L, oldCred.authorizationGeneration());

        connections.disconnect(userOneId);
        var newCred = connect(userOneId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        assertEquals(1L, newCred.authorizationGeneration());
        assertNotEquals(oldCred.connectionId(), newCred.connectionId());

        assertThrows(StaleSpotifyAuthorizationException.class,
                () -> writer.writeBatch(userOneId, oldCred, sampleItems(), OffsetDateTime.now()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
    }

    @Test
    void rejectsStaleGenerationDisabledUserAndMissingScope() {
        var oldCred = connect(userOneId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        connect(userOneId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);

        assertThrows(StaleSpotifyAuthorizationException.class,
                () -> writer.writeBatch(userOneId, oldCred, sampleItems(), OffsetDateTime.now()));

        var wrongScope = connect(userTwoId, "user-library-read");
        assertThrows(StaleSpotifyAuthorizationException.class,
                () -> writer.writeBatch(userTwoId, wrongScope, sampleItems(), OffsetDateTime.now()));

        var validTwo = connect(userTwoId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        jdbc.update("UPDATE users SET active = false WHERE user_id = ?", userTwoId);
        assertThrows(StaleSpotifyAuthorizationException.class,
                () -> writer.writeBatch(userTwoId, validTwo, sampleItems(), OffsetDateTime.now()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM Listens", Integer.class));
    }

    @Test
    void handlesConcurrentWritesWithoutDuplicatesOrDeadlocks() throws Exception {
        var cred1 = connect(userOneId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        var cred2 = connect(userTwoId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            List<Callable<Void>> tasks = List.of(
                    () -> { start.await(); writer.writeBatch(userOneId, cred1, sampleItems(), OffsetDateTime.now()); return null; },
                    () -> { start.await(); writer.writeBatch(userOneId, cred1, sampleItems(), OffsetDateTime.now()); return null; },
                    () -> { start.await(); writer.writeBatch(userTwoId, cred2, sampleItems(), OffsetDateTime.now()); return null; },
                    () -> { start.await(); writer.writeBatch(userTwoId, cred2, sampleItems(), OffsetDateTime.now()); return null; }
            );
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userTwoId));
        } finally {
            pool.shutdownNow();
        }
    }

    // A reativação invalida lotes antigos mesmo mantendo a mesma conexão e geração.
    @Test
    void rejectsBatchCapturedBeforeAccountReactivationWithoutWritingAnyState() {
        var oldCred = connect(userOneId, SpotifyAuthorizationService.RECENT_LISTENS_SCOPE);
        jdbc.update("UPDATE users SET active = false, password_hash = ? WHERE user_id = ?",
                passwords.encode("fixture-password"), userOneId);
        reactivation.reactivate(new LoginDto("echoes1@example.com", "fixture-password"));
        var row = connectionRepo.findByUserId(userOneId).orElseThrow();
        assertEquals(oldCred.connectionId(), row.getId());
        assertEquals(oldCred.authorizationGeneration(), row.getAuthorizationGeneration());
        assertThrows(StaleSpotifyAuthorizationException.class,
                () -> writer.writeBatch(userOneId, oldCred, sampleItems(), OffsetDateTime.now()));
        for (String table : List.of("Artists", "Albums", "Tracks", "Track_Artists", "Listens", "echo_sync_state")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class), table);
        }
        var freshCred = new SpotifyAccessCredential(oldCred.accessToken(), row.getId(),
                row.getAuthorizationGeneration(), users.findById(userOneId).orElseThrow().getSpotifyAuthorizationVersion(),
                row.getScopes());
        assertEquals(2, writer.writeBatch(userOneId, freshCred, sampleItems(), OffsetDateTime.now()).imported());
    }
}

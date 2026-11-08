package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JPA transactions and row locks in an isolated H2 database; no Spotify calls. */
@SpringJUnitConfig(SpotifyConnectionTransactionTest.Config.class)
@TestPropertySource(properties = {
        "app.frontend-url=http://localhost:3000", "api.security.token.secret=test-secret",
        "spring.mail.username=noreply@example.com",
        "sonarfy.test.jdbc-url=jdbc:h2:mem:spotify_transactions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spotify_token_encryption_key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spotify_token_encryption_keyring=legacy=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=,rotated=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "spotify_token_encryption_active_key_id=rotated"
})
class SpotifyConnectionTransactionTest {
    @Configuration
    @Import({SpotifyConnectionService.class, SpotifyTokenEncryptionService.class,
            SpotifyTokenKeyRotationService.class})
    static class Config extends EmailReservationIntegrationTest.Config {}

    @Autowired SpotifyConnectionService service;
    @Autowired UserRepository users;
    @Autowired br.com.pablohcarmo.sonarfy.repositories.PermissionRepository permissions;
    @Autowired SpotifyConnectionRepository connections;
    @Autowired SpotifyTokenEncryptionService encryption;
    @Autowired SpotifyTokenKeyRotationService keyRotation;
    @Autowired PlatformTransactionManager transactions;
    @PersistenceContext EntityManager em;

    private final SpotifyTokenResponseDto tokens =
            new SpotifyTokenResponseDto("access", "Bearer", 3600L, "refresh", null);

    private Long createUser() {
        var u = new User();
        var role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            var p = new br.com.pablohcarmo.sonarfy.entities.Permission();
            p.setName("ROLE_USER");
            return permissions.saveAndFlush(p);
        });
        u.setPermission(role);
        u.setHandle(UUID.randomUUID().toString().substring(0, 12));
        u.setEmail(u.getHandle() + "@example.com");
        u.setDisplayName("Spotify test");
        u.setActive(true);
        u.setVerified(true);
        return users.saveAndFlush(u).getId();
    }

    @Test
    void disconnectRejectsConsumedOldAttemptAndAllowsNewAuthorization() {
        Long id = createUser();
        service.connect(id, 0L, tokens);
        service.disconnect(id);
        assertTrue(connections.findByUserId(id).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.connect(id, 0L, tokens));
        service.connect(id, 1L, tokens);
        assertEquals(1, connections.countByUserId(id));
        service.disconnect(id);
        service.disconnect(id);
        assertEquals(3L, users.findById(id).orElseThrow().getSpotifyAuthorizationVersion());
    }

    @Test
    void rollbackRestoresBothVersionAndCredentials() {
        Long id = createUser();
        service.connect(id, 0L, tokens);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.disconnect(id);
            tx.setRollbackOnly();
        });
        assertEquals(0L, users.findById(id).orElseThrow().getSpotifyAuthorizationVersion());
        assertEquals(1, connections.countByUserId(id));
    }

    @Test
    void keyReencryptionRunsInResumableIdempotentAtomicBatches() {
        Long firstUser = createUser();
        Long secondUser = createUser();
        service.connect(firstUser, 0L, tokens);
        service.connect(secondUser, 0L, tokens);

        var legacyEncryption = new SpotifyTokenEncryptionService(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        replaceWithLegacyTokens(firstUser, legacyEncryption, "first");
        replaceWithLegacyTokens(secondUser, legacyEncryption, "second");
        assertEquals(4L, keyRotation.tokenCountsByKey().get("v1:legacy"));

        assertEquals(1, keyRotation.reencryptNextBatch(1));
        assertEquals(2L, keyRotation.tokenCountsByKey().get("v1:legacy"));
        assertEquals(2L, keyRotation.tokenCountsByKey().get("v2:rotated"));

        assertEquals(1, keyRotation.reencryptNextBatch(1));
        assertEquals(0L, keyRotation.countTokensNeedingReencryption());
        assertEquals(0, keyRotation.reencryptNextBatch(1));
        assertEquals(0L, keyRotation.tokenCountsByKey().get("v1:legacy"));
        assertEquals("first-access", encryption.decrypt(
                connections.findByUserId(firstUser).orElseThrow().getAccessTokenEncrypted(),
                firstUser,
                "access"));
        assertEquals("second-refresh", encryption.decrypt(
                connections.findByUserId(secondUser).orElseThrow().getRefreshTokenEncrypted(),
                secondUser,
                "refresh"));
    }

    private void replaceWithLegacyTokens(
            Long userId,
            SpotifyTokenEncryptionService legacyEncryption,
            String prefix
    ) {
        var connection = connections.findByUserId(userId).orElseThrow();
        connection.reencryptTokens(
                legacyEncryption.encrypt(prefix + "-access", userId, "access"),
                legacyEncryption.encrypt(prefix + "-refresh", userId, "refresh")
        );
        connections.saveAndFlush(connection);
    }

    @Test
    void inFlightCallbackWithCachedUserCannotReconnectAfterConcurrentDisconnect() throws Exception {
        Long id = createUser();
        service.connect(id, 0L, tokens);
        var cached = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var callback = executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                assertEquals(0L, em.find(User.class, id).getSpotifyAuthorizationVersion());
                cached.countDown();
                try {
                    assertTrue(disconnected.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                var failure = assertThrows(ResponseStatusException.class, () -> service.connect(id, 0L, tokens));
                tx.setRollbackOnly();
                return failure;
            }));
            assertTrue(cached.await(5, TimeUnit.SECONDS));
            service.disconnect(id);
            disconnected.countDown();
            assertEquals(400, callback.get(10, TimeUnit.SECONDS).getStatusCode().value());
            assertTrue(connections.findByUserId(id).isEmpty());
        } finally {
            disconnected.countDown();
            executor.shutdownNow();
        }
    }
}

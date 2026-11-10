package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.RefreshToken;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.RefreshTokenRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

// Integração com proxies transacionais, JPA e H2 isolado.
@SpringJUnitConfig(RefreshTokenRepositoryServiceTest.Config.class)
@TestPropertySource(properties = {
        "app.frontend-url=http://localhost:3000",
        "api.security.token.secret=test-secret",
        "spring.mail.username=noreply@example.com",
        "sonarfy.test.jdbc-url=jdbc:h2:mem:refresh_tokens;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class RefreshTokenRepositoryServiceTest {

    @Configuration
    @Import(RefreshTokenService.class)
    static class Config extends EmailReservationIntegrationTest.Config {
    }

    @Autowired RefreshTokenService service;
    @Autowired RefreshTokenRepository tokens;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;

    private User createUser() {
        Permission role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission p = new Permission();
            p.setName("ROLE_USER");
            return permissions.saveAndFlush(p);
        });
        User user = new User();
        user.setPermission(role);
        user.setHandle(UUID.randomUUID().toString().substring(0, 12));
        user.setEmail(user.getHandle() + "@example.com");
        user.setDisplayName("Refresh test");
        user.setActive(true);
        user.setVerified(true);
        return users.saveAndFlush(user);
    }

    private RefreshToken stored(String rawToken) {
        return tokens.findWithUserByTokenHash(RefreshTokenService.hash(rawToken)).orElseThrow();
    }

    private void assertUnauthorized(String rawToken) {
        var ex = assertThrows(ResponseStatusException.class, () -> service.rotate(rawToken));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    // O banco guarda apenas o hash, com validade de 30 dias.
    @Test
    void issueStoresOnlyHashWithInactivityExpiry() {
        String raw = service.issue(createUser());
        RefreshToken token = stored(raw);

        assertEquals(43, raw.length());
        assertNotEquals(raw, token.getTokenHash());
        assertEquals(64, token.getTokenHash().length());
        OffsetDateTime expected = OffsetDateTime.now().plus(RefreshTokenService.INACTIVITY_TTL);
        assertTrue(Duration.between(token.getExpiresAt(), expected).abs().toMinutes() < 1);
    }

    // A renovação devolve um token novo e invalida o anterior.
    @Test
    void rotateReplacesTokenAndRejectsPreviousOne() {
        User user = createUser();
        String raw = service.issue(user);

        var rotation = service.rotate(raw);

        assertEquals(user.getId(), rotation.user().getId());
        assertNotEquals(raw, rotation.refreshToken());
        assertUnauthorized(raw);
        assertNotNull(service.rotate(rotation.refreshToken()).refreshToken());
        assertEquals(1, tokens.countByUserId(user.getId()));
    }

    // A renovação estende a validade a partir do momento do uso.
    @Test
    void rotateExtendsExpiry() {
        String raw = service.issue(createUser());
        RefreshToken token = stored(raw);
        token.setExpiresAt(OffsetDateTime.now().plusDays(1));
        tokens.saveAndFlush(token);

        String next = service.rotate(raw).refreshToken();

        assertTrue(stored(next).getExpiresAt().isAfter(OffsetDateTime.now().plusDays(29)));
    }

    // Token expirado é recusado e removido.
    @Test
    void rotateRejectsAndDeletesExpiredToken() {
        User user = createUser();
        String raw = service.issue(user);
        RefreshToken token = stored(raw);
        token.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
        tokens.saveAndFlush(token);

        assertUnauthorized(raw);
        assertEquals(0, tokens.countByUserId(user.getId()));
    }

    // Conta desativada ou não verificada perde a sessão.
    @Test
    void rotateRejectsAndDeletesTokenOfDisabledUser() {
        User user = createUser();
        String raw = service.issue(user);
        user.setVerified(false);
        users.saveAndFlush(user);

        assertUnauthorized(raw);
        assertEquals(0, tokens.countByUserId(user.getId()));
    }

    // Entradas ausentes ou desconhecidas recebem o mesmo 401.
    @Test
    void rotateRejectsBlankOrUnknownToken() {
        assertUnauthorized(null);
        assertUnauthorized("  ");
        assertUnauthorized("unknown-token");
    }

    // Logout é idempotente e invalida o token.
    @Test
    void revokeIsIdempotent() {
        User user = createUser();
        String raw = service.issue(user);

        service.revoke(raw);
        service.revoke(raw);
        service.revoke(null);

        assertUnauthorized(raw);
        assertEquals(0, tokens.countByUserId(user.getId()));
    }

    // Revogação geral encerra todos os dispositivos apenas do usuário informado.
    @Test
    void revokeAllRemovesOnlyTargetUserSessions() {
        User owner = createUser();
        User other = createUser();
        String first = service.issue(owner);
        String second = service.issue(owner);
        String foreign = service.issue(other);

        service.revokeAll(owner.getId());

        assertUnauthorized(first);
        assertUnauthorized(second);
        assertNotNull(service.rotate(foreign).refreshToken());
    }

    // Renovações simultâneas do mesmo token produzem um único vencedor.
    @Test
    void concurrentRotationHasSingleWinner() throws Exception {
        User user = createUser();
        String raw = service.issue(user);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    service.rotate(raw);
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(attempt);
            Future<Boolean> b = pool.submit(attempt);
            start.countDown();

            int winners = (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(1, tokens.countByUserId(user.getId()));
        } finally {
            pool.shutdownNow();
        }
    }
}

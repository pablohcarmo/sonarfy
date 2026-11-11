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
        @org.springframework.beans.factory.annotation.Value("${sonarfy.refresh.test.ddl:create-drop}")
        String schemaMode;

        // PostgreSQL valida o schema importado; H2 cria um banco isolado.
        @org.springframework.context.annotation.Bean
        @Override
        org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(javax.sql.DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", schemaMode));
            return factory;
        }
    }

    @Autowired RefreshTokenService service;
    @Autowired RefreshTokenRepository tokens;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private User createUser() {
        Permission role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission p = new Permission();
            p.setName("ROLE_USER");
            return permissions.saveAndFlush(p);
        });
        User user = new User();
        user.setName("Refresh");
        user.setSurname("Test");
        user.setPassword("test-hash");
        user.setCity("Brasilia");
        user.setCountry("Brasil");
        user.setBirthDate(java.time.LocalDate.of(2000, 1, 1));
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

        assertEquals(user.getId(), rotation.userId());
        assertNotEquals(raw, rotation.refreshToken());
        assertFalse(rotation.toString().contains(rotation.refreshToken()));
        String next = service.rotate(rotation.refreshToken()).refreshToken();
        assertEquals(3, tokens.countByUserId(user.getId()));
        assertUnauthorized(raw);
        assertUnauthorized(next);
        assertEquals(0, tokens.countByUserId(user.getId()));
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

    // Uma renovação vence; a reutilização concorrente encerra a sessão emitida.
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
                } catch (ResponseStatusException e) {
                    assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(attempt);
            Future<Boolean> b = pool.submit(attempt);
            start.countDown();

            int winners = (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(0, tokens.countByUserId(user.getId()));
        } finally {
            pool.shutdownNow();
        }
    }
    // Reutilização encerra apenas a sessão afetada e preserva outro dispositivo.
    @Test
    void replayPreservesOtherDevice() {
        User user = createUser();
        String old = service.issue(user);
        String other = service.issue(user);
        String current = service.rotate(old).refreshToken();
        assertUnauthorized(old);
        assertUnauthorized(current);
        assertNotNull(service.rotate(other).refreshToken());
    }

    // Logout reconhece o token antigo e remove o sucessor já emitido.
    @Test
    void logoutWithConsumedTokenRevokesSuccessor() {
        String old = service.issue(createUser());
        String current = service.rotate(old).refreshToken();
        service.revoke(old);
        assertUnauthorized(current);
    }

    // Impede que o 401 confirme alterações pendentes de uma transação do chamador.
    @Test
    void rotationRejectsAmbientTransactionWithoutConsumingToken() {
        String raw = service.issue(createUser());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> transaction.execute(status -> service.rotate(raw)));
        assertNotNull(service.rotate(raw).refreshToken());
    }

    // Qualquer ordem entre logout e rotação termina com a sessão encerrada.
    @Test
    void concurrentLogoutAndRotationLeaveNoActiveSession() throws Exception {
        User user = createUser();
        String raw = service.issue(user);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> rotating = pool.submit(() -> {
                start.await();
                try {
                    return service.rotate(raw).refreshToken();
                } catch (ResponseStatusException e) {
                    assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
                    return null;
                }
            });
            Future<?> logout = pool.submit(() -> {
                start.await();
                service.revoke(raw);
                return null;
            });
            start.countDown();
            String successor = rotating.get(10, TimeUnit.SECONDS);
            logout.get(10, TimeUnit.SECONDS);
            if (successor != null) assertUnauthorized(successor);
            assertEquals(0, tokens.countByUserId(user.getId()));
        }
    }

}

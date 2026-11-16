package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.ChangePasswordDto;
import br.com.pablohcarmo.sonarfy.dto.ResetPasswordDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real signed tokens, Spring transactions and JPA locks; no mocked password validation. */
@SpringJUnitConfig(EmailReservationIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "app.frontend-url=http://localhost:3000",
        "api.security.token.secret=isolated-test-secret",
        "spring.mail.username=noreply@example.com"
})
class PasswordResetIntegrationTest {
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired UserService service;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @Autowired RefreshTokenService refreshTokens;
    @Autowired br.com.pablohcarmo.sonarfy.repositories.RefreshTokenRepository sessions;
    private Long id;
    private Instant issuedAt;
    private OffsetDateTime originalCutoff;
    private String resetToken;
    private String changeToken;
    private String originalHash;

    @BeforeEach
    void prepare() {
        sessions.deleteAll();
        users.deleteAll();
        Permission role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission p = new Permission();
            p.setName("ROLE_USER");
            return permissions.save(p);
        });
        User user = new User();
        user.setName("Password");
        user.setSurname("Test");
        user.setCity("Brasilia");
        user.setCountry("Brasil");
        user.setBirthDate(LocalDate.of(2000, 1, 1));
        user.setDisplayName("Password Test");
        user.setPermission(role);
        user.setHandle("password-test");
        user.setEmail("password@example.com");
        user.setVerified(true);
        originalHash = encoder.encode("OldPassword123!");
        user.setPassword(originalHash);
        id = users.saveAndFlush(user).getId();
        issuedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        originalCutoff = issuedAt.minusSeconds(60).atOffset(ZoneOffset.UTC);
        setCutoff(originalCutoff);
        resetToken = signedToken(issuedAt, "password_reset", Instant.now().plusSeconds(600));
        changeToken = signedToken(issuedAt, "password_change", Instant.now().plusSeconds(600));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sequentialReplayWithDifferentNewPasswordIsRejected(boolean authenticated) {
        change(authenticated, authenticated ? changeToken : resetToken, "FirstPassword123!");
        User saved = users.findById(id).orElseThrow();
        assertTrue(encoder.matches("FirstPassword123!", saved.getPassword()));
        assertEquals(0, saved.getPasswordLastUpdateDate().getNano());
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> change(authenticated, authenticated ? changeToken : resetToken, "SecondPassword123!"));
        assertEquals(400, rejected.getStatusCode().value());
        assertTrue(rejected.getReason().contains("already been used"));
        assertEquals(saved.getPassword(), users.findById(id).orElseThrow().getPassword());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reset", "change", "mixed"})
    void concurrentUseHasExactlyOneWinner(String mode) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> compete(start, !mode.equals("reset"), "FirstPassword123!"));
            Future<Boolean> second = pool.submit(() -> compete(start, mode.equals("change"), "SecondPassword123!"));
            boolean firstWon = first.get(15, TimeUnit.SECONDS);
            boolean secondWon = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon, "Exactly one transaction must commit");
            User saved = users.findById(id).orElseThrow();
            assertTrue(encoder.matches(firstWon ? "FirstPassword123!" : "SecondPassword123!", saved.getPassword()));
            assertFalse(encoder.matches(firstWon ? "SecondPassword123!" : "FirstPassword123!", saved.getPassword()));
            assertFalse(saved.getPasswordLastUpdateDate().toInstant().isBefore(issuedAt));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void issuanceMustBeStrictlyAfterPasswordCutoff(int secondsAfterCutoff) {
        setCutoff(issuedAt.atOffset(ZoneOffset.UTC));
        String boundary = signedToken(issuedAt.plusSeconds(secondsAfterCutoff),
                "password_reset", Instant.now().plusSeconds(600));
        if (secondsAfterCutoff > 0) {
            change(false, boundary, "FirstPassword123!");
            assertTrue(encoder.matches("FirstPassword123!", users.findById(id).orElseThrow().getPassword()));
        } else {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> change(false, boundary, "FirstPassword123!")).getStatusCode().value());
            assertEquals(originalHash, users.findById(id).orElseThrow().getPassword());
        }
    }

    // PostgreSQL exige cutoff não nulo; o caso legado nullable continua coberto no H2.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingIssuedAtIsRejectedRegardlessOfStoredCutoff(boolean nullCutoff) throws Exception {
        if (nullCutoff) {
            try (var connection = dataSource.getConnection()) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        connection.getMetaData().getDatabaseProductName().equals("H2"));
            }
            setCutoff(null);
        }
        String missing = signedToken(null, "password_reset", Instant.now().plusSeconds(600));
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> change(false, missing, "FirstPassword123!"));
        assertEquals("Token issuance date is required!", rejected.getReason());
        assertEquals(originalHash, users.findById(id).orElseThrow().getPassword());
        if (nullCutoff) assertNull(users.findById(id).orElseThrow().getPasswordLastUpdateDate());
        else assertUnchanged();
    }

    @Test
    void expiredAndWrongPurposeTokensAreRejected() {
        for (String invalid : new String[] {
                signedToken(issuedAt, "password_reset", Instant.now().minusSeconds(1)),
                signedToken(issuedAt, "email_confirmation", Instant.now().plusSeconds(600)),
                changeToken
        }) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> change(false, invalid, "FirstPassword123!")).getStatusCode().value());
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> change(true, resetToken, "FirstPassword123!")).getStatusCode().value());
        assertUnchanged();
    }

    @Test
    void validationFailuresDoNotConsumeToken() {
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.changePassword(id + 1,
                        new ChangePasswordDto(changeToken, "OldPassword123!", "FirstPassword123!", "FirstPassword123!")))
                .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.changePassword(id,
                        new ChangePasswordDto(changeToken, "wrong", "FirstPassword123!", "FirstPassword123!")))
                .getStatusCode().value());
        assertThrows(ResponseStatusException.class,
                () -> service.resetPassword(new ResetPasswordDto(resetToken, "FirstPassword123!", "Mismatch")));
        assertThrows(ResponseStatusException.class, () -> change(false, resetToken, "OldPassword123!"));
        assertUnchanged();
        change(true, changeToken, "FirstPassword123!");
        assertTrue(encoder.matches("FirstPassword123!", users.findById(id).orElseThrow().getPassword()));
    }

    @Test
    void rollbackRestoresPasswordAndCutoffAndAllowsRetry() {
        String refresh = refreshTokens.issue(users.findById(id).orElseThrow());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    change(false, resetToken, "FirstPassword123!");
                    users.flush();
                    throw new IllegalStateException("Force rollback after SQL update");
                }));
        assertUnchanged();
        assertNotNull(refreshTokens.rotate(refresh));
        change(false, resetToken, "SecondPassword123!");
        assertTrue(encoder.matches("SecondPassword123!", users.findById(id).orElseThrow().getPassword()));
    }

    // Os dois fluxos encerram todos os dispositivos e históricos; outro usuário permanece autenticado.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordUpdateRevokesEverySession(boolean authenticated) {
        User owner = users.findById(id).orElseThrow();
        String first = refreshTokens.issue(owner, true);
        String successor = refreshTokens.rotate(first).refreshToken();
        String second = refreshTokens.issue(owner, false);
        User other = new User(null, owner.getPermission(), "Other", "Account", "Other Account",
                "other-password", "other@example.com", originalHash, true, true, "Brasilia", "Brasil",
                null, null, null, LocalDate.of(2000, 1, 1), null, null, null, null);
        String foreign = refreshTokens.issue(users.saveAndFlush(other));
        change(authenticated, authenticated ? changeToken : resetToken, "FirstPassword123!");
        assertEquals(0, sessions.countByUserId(id));
        for (String token : new String[] {first, successor, second}) assertRefreshRejected(token);
        assertNotNull(refreshTokens.rotate(foreign));
        assertTrue(encoder.matches("FirstPassword123!", users.findById(id).orElseThrow().getPassword()));
        assertNotNull(refreshTokens.rotate(refreshTokens.issue(users.findById(id).orElseThrow())));
    }

    // Validação recusada não revoga a sessão nem altera a senha.
    @Test
    void invalidPasswordChangePreservesSession() {
        String refresh = refreshTokens.issue(users.findById(id).orElseThrow());
        assertThrows(ResponseStatusException.class, () -> service.changePassword(id,
                new ChangePasswordDto(changeToken, "wrong", "FirstPassword123!", "FirstPassword123!")));
        assertNotNull(refreshTokens.rotate(refresh));
        assertUnchanged();
    }

    // Seja qual for o vencedor do bloqueio, nenhum sucessor anterior à recuperação sobrevive.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordUpdateCompetesWithRefresh(boolean authenticated) throws Exception {
        String old = refreshTokens.issue(users.findById(id).orElseThrow());
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> rotating = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try { return refreshTokens.rotate(old).refreshToken(); }
                catch (ResponseStatusException ex) {
                    assertEquals(401, ex.getStatusCode().value());
                    return null;
                }
            });
            Future<?> resetting = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                change(authenticated, authenticated ? changeToken : resetToken, "FirstPassword123!");
                return null;
            });
            resetting.get(15, TimeUnit.SECONDS);
            String successor = rotating.get(15, TimeUnit.SECONDS);
            assertRefreshRejected(old);
            if (successor != null) assertRefreshRejected(successor);
            assertEquals(0, sessions.countByUserId(id));
            assertTrue(encoder.matches("FirstPassword123!", users.findById(id).orElseThrow().getPassword()));
        }
    }

    private void assertRefreshRejected(String token) {
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> refreshTokens.rotate(token)).getStatusCode().value());
    }

    @Test
    void refreshRejectsReplayWhenUserWasLoadedBeforeAnotherTransaction() {
        assertThrows(ResponseStatusException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    User cached = users.findById(id).orElseThrow();
                    try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                        pool.submit(() -> change(false, resetToken, "FirstPassword123!")).get(15, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    assertEquals(originalHash, cached.getPassword(), "Persistence context still has old state");
                    change(false, resetToken, "SecondPassword123!");
                }));
        assertTrue(encoder.matches("FirstPassword123!", users.findById(id).orElseThrow().getPassword()));
    }

    private boolean compete(CyclicBarrier start, boolean authenticated, String password) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            change(authenticated, authenticated ? changeToken : resetToken, password);
            return true;
        } catch (ResponseStatusException rejected) {
            assertEquals(400, rejected.getStatusCode().value());
            assertTrue(rejected.getReason().contains("already been used"));
            return false;
        }
    }

    private void change(boolean authenticated, String value, String password) {
        if (authenticated) {
            service.changePassword(id,
                    new ChangePasswordDto(value, "OldPassword123!", password, password));
        } else {
            service.resetPassword(new ResetPasswordDto(value, password, password));
        }
    }

    private String signedToken(Instant issuance, String purpose, Instant expiration) {
        var builder = JWT.create().withIssuer("sonarfy-api").withSubject(id.toString())
                .withClaim("purpose", purpose).withExpiresAt(expiration);
        if (issuance != null) builder.withIssuedAt(issuance);
        return builder.sign(Algorithm.HMAC256("isolated-test-secret"));
    }

    private void setCutoff(OffsetDateTime cutoff) {
        new JdbcTemplate(dataSource).update("UPDATE users SET password_updated_at = ? WHERE user_id = ?", cutoff, id);
    }

    private void assertUnchanged() {
        User saved = users.findById(id).orElseThrow();
        assertEquals(originalHash, saved.getPassword());
        assertEquals(originalCutoff.toInstant(), saved.getPasswordLastUpdateDate().toInstant());
    }
}

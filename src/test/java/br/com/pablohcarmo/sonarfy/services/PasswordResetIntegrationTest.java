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
    private Long id;
    private Instant issuedAt;
    private OffsetDateTime originalCutoff;
    private String token;
    private String originalHash;

    @BeforeEach
    void prepare() {
        users.deleteAll();
        Permission role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission p = new Permission();
            p.setName("ROLE_USER");
            return permissions.save(p);
        });
        User user = new User();
        user.setPermissionId(role);
        user.setHandle("password-test");
        user.setEmail("password@example.com");
        user.setVerified(true);
        originalHash = encoder.encode("OldPassword123!");
        user.setPassword(originalHash);
        id = users.saveAndFlush(user).getId();
        issuedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        originalCutoff = issuedAt.minusSeconds(60).atOffset(ZoneOffset.UTC);
        setCutoff(originalCutoff);
        token = signedToken(issuedAt, "password_reset", Instant.now().plusSeconds(600));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sequentialReplayWithDifferentNewPasswordIsRejected(boolean authenticated) {
        change(authenticated, token, "FirstPassword123!");
        User saved = users.findById(id).orElseThrow();
        assertTrue(encoder.matches("FirstPassword123!", saved.getPassword()));
        assertEquals(0, saved.getPasswordLastUpdateDate().getNano());
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> change(authenticated, token, "SecondPassword123!"));
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

    @Test
    void missingIssuedAtIsRejectedEvenWithNullStoredCutoff() {
        setCutoff(null);
        String missing = signedToken(null, "password_reset", Instant.now().plusSeconds(600));
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> change(false, missing, "FirstPassword123!"));
        assertEquals("Token issuance date is required!", rejected.getReason());
        assertEquals(originalHash, users.findById(id).orElseThrow().getPassword());
        assertNull(users.findById(id).orElseThrow().getPasswordLastUpdateDate());
    }

    @Test
    void expiredAndWrongPurposeTokensAreRejected() {
        for (String invalid : new String[] {
                signedToken(issuedAt, "password_reset", Instant.now().minusSeconds(1)),
                signedToken(issuedAt, "email_confirmation", Instant.now().plusSeconds(600))
        }) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> change(false, invalid, "FirstPassword123!")).getStatusCode().value());
        }
        assertUnchanged();
    }

    @Test
    void validationFailuresDoNotConsumeToken() {
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.changePassword("other@example.com",
                        new ChangePasswordDto(token, "OldPassword123!", "FirstPassword123!", "FirstPassword123!")))
                .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.changePassword("password@example.com",
                        new ChangePasswordDto(token, "wrong", "FirstPassword123!", "FirstPassword123!")))
                .getStatusCode().value());
        assertThrows(ResponseStatusException.class,
                () -> service.resetPassword(new ResetPasswordDto(token, "FirstPassword123!", "Mismatch")));
        assertThrows(ResponseStatusException.class, () -> change(false, token, "OldPassword123!"));
        assertUnchanged();
        change(true, token, "FirstPassword123!");
        assertTrue(encoder.matches("FirstPassword123!", users.findById(id).orElseThrow().getPassword()));
    }

    @Test
    void rollbackRestoresPasswordAndCutoffAndAllowsRetry() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    change(false, token, "FirstPassword123!");
                    users.flush();
                    throw new IllegalStateException("Force rollback after SQL update");
                }));
        assertUnchanged();
        change(false, token, "SecondPassword123!");
        assertTrue(encoder.matches("SecondPassword123!", users.findById(id).orElseThrow().getPassword()));
    }

    @Test
    void refreshRejectsReplayWhenUserWasLoadedBeforeAnotherTransaction() {
        assertThrows(ResponseStatusException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    User cached = users.findById(id).orElseThrow();
                    try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                        pool.submit(() -> change(false, token, "FirstPassword123!")).get(15, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    assertEquals(originalHash, cached.getPassword(), "Persistence context still has old state");
                    change(false, token, "SecondPassword123!");
                }));
        assertTrue(encoder.matches("FirstPassword123!", users.findById(id).orElseThrow().getPassword()));
    }

    private boolean compete(CyclicBarrier start, boolean authenticated, String password) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            change(authenticated, token, password);
            return true;
        } catch (ResponseStatusException rejected) {
            assertEquals(400, rejected.getStatusCode().value());
            assertTrue(rejected.getReason().contains("already been used"));
            return false;
        }
    }

    private void change(boolean authenticated, String value, String password) {
        if (authenticated) {
            service.changePassword("password@example.com",
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

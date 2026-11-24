package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.*;
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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// PostgreSQL descartável e serviços reais; não instancia controller ou transporte SMTP.
@EnabledIfSystemProperty(named = "sonarfy.test.deactivation-postgres", matches = "true")
@SpringJUnitConfig(AccountDeactivationRequestPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=deactivation-request-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class AccountDeactivationRequestPostgresTest {
    @Configuration @Import({AccountDeactivationRequestService.class, AccountDeactivationMailRepository.class})
    static class Config extends AccountDeactivationInvalidationPostgresTest.Config {}
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired RefreshTokenService refresh;
    @Autowired AccountDeactivationRequestService service;
    @Autowired AccountDeactivationRequestRepository requests;
    @MockitoSpyBean AccountDeactivationMailRepository mails;
    @Autowired PlatformTransactionManager manager;
    long actor;
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    void cooldownFree() { db.update("UPDATE users SET last_email_sent_at = NULL WHERE user_id = ?", actor); }
    @BeforeEach void fixture() {
        reset(mails); users.deleteAll();
        actor = db.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Conta', 'Teste', 'Conta Teste', 'request-test', 'request@example.com', ?, 'Campinas', 'Brasil', '1990-01-01', true)
                RETURNING user_id
                """, Long.class, passwords.encode("Current123!"));
        refresh.issue(users.findById(actor).orElseThrow());
    }

    // Aceite persiste destinatário atual e intenção sem alterar a conta; retentativa não renova o prazo.
    @Test void requestsAndRetriesWithoutPersistingSecretOrExtendingExpiry() {
        service.request(actor, "Current123!");
        assertTrue(db.queryForObject("SELECT active AND verified AND access_token_version = 0 FROM users WHERE user_id = ?", Boolean.class, actor));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, actor));
        var first = mails.claim().orElseThrow();
        assertEquals("request@example.com", first.recipient());
        assertFalse(first.toString().contains(first.authorization().token()));
        assertTrue(mails.claim().isEmpty()); assertTrue(mails.complete(first, false));
        db.update("UPDATE account_deactivation_mail_outbox SET available_at = statement_timestamp()");
        var retry = mails.claim().orElseThrow();
        assertEquals(first.authorization().expiresAt(), retry.authorization().expiresAt());
        assertNotEquals(first.authorization().token(), retry.authorization().token());
        assertFalse(mails.complete(first, true));
        assertEquals(Optional.empty(), tx().execute(status -> requests.consume(first.authorization().token())));
        assertTrue(mails.complete(retry, true)); assertTrue(mails.claim().isEmpty());
        assertEquals(Optional.of(actor), tx().execute(status -> requests.consume(retry.authorization().token())));
    }

    // Limite BCrypt é medido em bytes; campos inválidos e conta inelegível não criam intenções.
    @ParameterizedTest @ValueSource(strings = {"blank", "oversized", "inactive", "unverified"})
    void rejectsInvalidInputAndIneligibleAccount(String scenario) {
        String password = scenario.equals("blank") ? " " : scenario.equals("oversized") ? "é".repeat(37) : "Current123!";
        if (scenario.equals("inactive")) db.update("UPDATE users SET active = false");
        if (scenario.equals("unverified")) db.update("UPDATE users SET verified = false");
        assertEquals(scenario.equals("blank") ? 400 : 401,
                assertThrows(ResponseStatusException.class, () -> service.request(actor, password)).getStatusCode().value());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM account_deactivation_mail_outbox", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM account_deactivation_requests", Integer.class));
    }

    // Senha incorreta e cooldown não substituem a autorização; falha de outbox reverte a substituição.
    @Test void rejectsCredentialsAndRollsBackRequestWhenEnqueueFails() {
        service.request(actor, "Current123!"); var original = mails.claim().orElseThrow();
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.request(actor, "Current123!")).getStatusCode().value());
        cooldownFree();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.request(actor, "Wrong123!")).getStatusCode().value());
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.request(actor, "Current123!")).getStatusCode().value());
        cooldownFree();
        tx().executeWithoutResult(status -> doThrow(new IllegalStateException("test failure")).when(mails).enqueue(anyLong(), any(), anyString()));
        assertThrows(IllegalStateException.class, () -> service.request(actor, "Current123!"));
        assertEquals(original.requestId(), db.queryForObject("SELECT request_id FROM account_deactivation_requests", UUID.class));
        assertEquals(Optional.of(actor), tx().execute(status -> requests.consume(original.authorization().token())));
    }

    // Eventos invalidados, expirados, substituídos ou destinados a conta inelegível não autorizam envio.
    @ParameterizedTest @ValueSource(strings = {"invalidate", "expire", "replace", "inactive", "unverified", "email"})
    void ignoresObsoleteOrIneligibleEvents(String change) {
        service.request(actor, "Current123!");
        switch (change) {
            case "invalidate" -> tx().executeWithoutResult(status -> requests.invalidate(actor));
            case "replace" -> tx().executeWithoutResult(status -> requests.replace(actor));
            case "expire" -> db.update("UPDATE account_deactivation_requests SET issued_at = statement_timestamp() - interval '16 minutes', expires_at = statement_timestamp()");
            case "inactive" -> db.update("UPDATE users SET active = false");
            case "unverified" -> db.update("UPDATE users SET verified = false");
            case "email" -> db.update("UPDATE users SET email = 'different@example.com'");
        }
        assertTrue(mails.claim().isEmpty());
    }

    // Dois trabalhadores não reservam a mesma tentativa; reserva abandonada rotaciona e rejeita o dono anterior.
    @Test void concurrentClaimAndAbandonedLease() throws Exception {
        service.request(actor, "Current123!");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<Optional<AccountDeactivationMailRepository.Delivery>> claim = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return mails.claim(); };
            var a = pool.submit(claim); var b = pool.submit(claim); start.countDown();
            var first = a.get(5, TimeUnit.SECONDS); var second = b.get(5, TimeUnit.SECONDS);
            assertNotEquals(first.isPresent(), second.isPresent());
            var old = first.orElseGet(second::orElseThrow);
            db.update("UPDATE account_deactivation_mail_outbox SET lease_until = statement_timestamp()");
            assertFalse(mails.complete(old, true));
            var current = mails.claim().orElseThrow();
            assertNotEquals(old.authorization().token(), current.authorization().token());
            assertEquals(old.authorization().expiresAt(), current.authorization().expiresAt());
            assertFalse(mails.complete(old, false)); assertTrue(mails.complete(current, true));
        }
    }
}

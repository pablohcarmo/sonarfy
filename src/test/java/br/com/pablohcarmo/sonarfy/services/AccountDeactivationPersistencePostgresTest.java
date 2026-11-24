package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.AccountDeactivationRequestRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

// Só acessa o PostgreSQL descartável dedicado; não importa a configuração da aplicação nem executa DDL.
@EnabledIfSystemProperty(named = "sonarfy.test.deactivation-postgres", matches = "true")
@SpringJUnitConfig(AccountDeactivationPersistencePostgresTest.Config.class)
class AccountDeactivationPersistencePostgresTest {
    @Configuration @EnableTransactionManagement @Import(AccountDeactivationRequestRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            int port = Integer.getInteger("sonarfy.test.deactivation-port", 55453);
            return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/sonarfy_deactivation_test",
                    "deactivation_test", "isolated-deactivation-test");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }
    @Autowired JdbcTemplate db;
    @Autowired AccountDeactivationRequestRepository requests;
    @Autowired PlatformTransactionManager manager;
    long actor, other;
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    AccountDeactivationRequestRepository.Issued issue(long id) { return tx().execute(status -> requests.replace(id)); }
    Optional<Long> consume(String token) { return tx().execute(status -> requests.consume(token)); }

    @BeforeEach void fixture() {
        db.update("DELETE FROM users WHERE handle IN ('deactivation-a', 'deactivation-b')");
        actor = account("deactivation-a"); other = account("deactivation-b");
        db.update("INSERT INTO user_following (follower_id, following_id) VALUES (?, ?)", actor, other);
        db.update("INSERT INTO refresh_tokens (user_id, token_hash, session_id, expires_at) VALUES (?, ?, ?, now() + interval '1 day')",
                actor, "b".repeat(64), UUID.randomUUID());
    }
    long account(String handle) {
        return db.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Conta', 'Teste', 'Conta Teste', ?, ?, 'test-hash', 'Campinas', 'Brasil', '1990-01-01', true) RETURNING user_id
                """, Long.class, handle, handle + "@example.com");
    }

    // Persiste apenas hashes, respeita quinze minutos e consome uma única vez sem afetar conta, relações ou sessões.
    @Test void hashesLifetimeSingleUseAndIsolation() {
        var first = issue(actor); var second = issue(other);
        assertNotEquals(first.token(), second.token()); assertFalse(first.toString().contains(first.token()));
        assertEquals(32, Base64.getUrlDecoder().decode(first.token().substring("deactivation.".length())).length);
        assertTrue(db.queryForObject("SELECT token_hash ~ '^[0-9a-f]{64}$' AND expires_at - issued_at = interval '15 minutes' FROM account_deactivation_requests WHERE user_id = ?", Boolean.class, actor));
        assertEquals(Optional.of(actor), consume(first.token())); assertTrue(consume(first.token()).isEmpty());
        assertEquals(Optional.of(other), consume(second.token()));
        assertTrue(db.queryForObject("SELECT active AND verified AND access_token_version = 0 FROM users WHERE user_id = ?", Boolean.class, actor));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM user_following WHERE follower_id = ?", Integer.class, actor));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, actor));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM reactivation_mail_outbox", Integer.class));
    }

    // Tokens de outro formato ou adulterados não autorizam o consumo da solicitação pendente.
    @Test void rejectsOtherPurposesAndTampering() {
        var request = issue(actor);
        for (String invalid : new String[]{null, "", "invalid", request.token().substring(13), "eyJhbGciOiJIUzI1NiJ9.payload.signature",
                "deactivation." + "A".repeat(43)}) assertTrue(consume(invalid).isEmpty());
        assertEquals(Optional.of(actor), consume(request.token()));
    }

    // Substituição e invalidação afetam somente a conta selecionada pela camada interna.
    @Test void replacementAndInvalidation() {
        var first = issue(actor); var foreign = issue(other); var current = issue(actor);
        assertNotEquals(first.id(), current.id()); assertTrue(consume(first.token()).isEmpty());
        tx().executeWithoutResult(status -> requests.invalidate(actor));
        assertTrue(consume(current.token()).isEmpty()); assertEquals(Optional.of(other), consume(foreign.token()));
    }

    // O limite de expiração é exclusivo e datas futuras não permitem antecipar uma confirmação.
    @Test void expiredOrFutureRequestCannotBeConsumed() {
        var request = issue(actor);
        db.update("UPDATE account_deactivation_requests SET issued_at = now() - interval '15 minutes', expires_at = now() WHERE user_id = ?", actor);
        assertTrue(consume(request.token()).isEmpty());
        db.update("UPDATE account_deactivation_requests SET issued_at = now() + interval '1 minute', expires_at = now() + interval '16 minutes' WHERE user_id = ?", actor);
        assertTrue(consume(request.token()).isEmpty());
    }

    // Obriga o chamador a compartilhar a transação e preserva tanto emissão quanto consumo em rollback.
    @Test void requiresTransactionAndRollsBack() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> requests.replace(actor));
        var rolledBack = tx().execute(status -> { var result = requests.replace(actor); status.setRollbackOnly(); return result; });
        assertTrue(consume(rolledBack.token()).isEmpty());
        var committed = issue(actor);
        tx().executeWithoutResult(status -> { requests.replace(actor); status.setRollbackOnly(); });
        tx().executeWithoutResult(status -> { assertEquals(Optional.of(actor), requests.consume(committed.token())); status.setRollbackOnly(); });
        assertEquals(Optional.of(actor), consume(committed.token()));
    }

    // Duas confirmações concorrentes têm um único vencedor e não recriam uma autorização consumida.
    @Test void concurrentConsumptionHasOneWinner() throws Exception {
        var request = issue(actor); var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Optional<Long>> call = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return consume(request.token()); };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS).isPresent(), second.get(5, TimeUnit.SECONDS).isPresent());
        }
    }

    // Uma espera pelo bloqueio não pode preservar um token substituído ou ultrapassar sua validade real.
    @ParameterizedTest @ValueSource(strings = {"replace", "expire"})
    void rechecksAfterWaitingForAccountLock(String change) throws Exception {
        var request = issue(actor);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = tx().execute(status -> {
                db.queryForObject("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, actor);
                var future = pool.submit(() -> consume(request.token()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    db.execute("SELECT pg_stat_clear_snapshot()");
                    if (db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class) > 0) break;
                    assertTrue(System.nanoTime() < deadline);
                    try { Thread.sleep(20); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                }
                if (change.equals("replace")) requests.replace(actor);
                else db.update("UPDATE account_deactivation_requests SET expires_at = clock_timestamp() WHERE user_id = ?", actor);
                return future;
            });
            assertTrue(waiting.get(5, TimeUnit.SECONDS).isEmpty());
        }
    }
}

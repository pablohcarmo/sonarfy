package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.EmailChangeRequestRepository;
import br.com.pablohcarmo.sonarfy.repositories.EmailChangeMailRepository;
import br.com.pablohcarmo.sonarfy.dto.EmailChangeRequestDto;
import br.com.pablohcarmo.sonarfy.dto.EmailChangeMailDto;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

// Somente PostgreSQL descartável: o setup remove exclusivamente a conta de fixture.
@EnabledIfSystemProperty(named = "sonarfy.test.email-change-postgres", matches = "true")
@SpringJUnitConfig(EmailChangePersistencePostgresTest.Config.class)
class EmailChangePersistencePostgresTest {
    @Configuration @EnableTransactionManagement @Import({EmailChangeRequestRepository.class, EmailChangeMailRepository.class, EmailChangeService.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getProperty("sonarfy.email-change.test.jdbc-url",
                    System.getenv().getOrDefault("SONARFY_EMAIL_CHANGE_TEST_JDBC_URL", ""));
            if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/sonarfy_email_change_validation")) {
                throw new IllegalArgumentException("A dedicated local email-change validation database is required.");
            }
            return new DriverManagerDataSource(url,
                    System.getenv().getOrDefault("SONARFY_TEST_DB_USER", "postgres"),
                    System.getenv().getOrDefault("SONARFY_TEST_DB_PASSWORD", ""));
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }
    @Autowired EmailChangeService service;
    @Autowired EmailChangeRequestRepository requests;
    @Autowired EmailChangeMailRepository mails;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-16T12:00:00Z");
    private long userId;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM users WHERE email = 'email-change-fixture@example.com'");
        userId = jdbc.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email,
                    password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Email', 'Fixture', 'Email Fixture', 'email-fixture', 'email-change-fixture@example.com',
                    'test-hash', 'Brasilia', 'Brasil', '2000-01-01', true) RETURNING user_id
                """, Long.class);
    }

    // Substituir invalida a autorização anterior sem efetivar a troca de endereço.
    @Test
    void replacementAndConsumptionAreSpecificAndSingleUse() {
        var first = request(); service.replaceRequest(first);
        var second = request(); service.replaceRequest(second);
        assertTrue(requests.findRequest(first.id()).isEmpty());
        assertEquals(second.newEmail(), requests.findRequest(second.id()).orElseThrow().newEmail());
        assertFalse(requests.consume(first.id(), now));
        assertTrue(requests.consume(second.id(), now));
        assertFalse(requests.consume(second.id(), now));
        assertEquals("email-change-fixture@example.com", jdbc.queryForObject(
                "SELECT email FROM users WHERE user_id = ?", String.class, userId));
        assertEquals(2, queued());
    }

    // O repository da solicitação não decide pelo enfileiramento de e-mail.
    @Test
    void requestRepositoryDoesNotEnqueueMail() {
        var request = request(); requests.replaceRequest(request);
        assertTrue(requests.findRequest(request.id()).isPresent());
        assertEquals(0, queued());
    }

    @Test
    void expiredRequestCannotBeConsumed() {
        var request = request(); service.replaceRequest(request);
        assertFalse(requests.consume(request.id(), request.expiresAt()));
        assertNull(requests.findRequest(request.id()).orElseThrow().consumedAt());
    }

    // Mesmo com duas confirmações concorrentes, só uma pode consumir a solicitação.
    @Test
    void concurrentConsumptionHasOneWinner() throws Exception {
        var request = request(); service.replaceRequest(request);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> consume = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return requests.consume(request.id(), now);
            };
            var first = pool.submit(consume); var second = pool.submit(consume); start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    // A solicitação e seu evento desaparecem juntos se a transação de negócio falhar.
    @Test
    void rollbackRemovesRequestAndMailTogether() {
        var request = request();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.replaceRequest(request);
            throw new IllegalStateException("test rollback");
        }));
        assertTrue(requests.findRequest(request.id()).isEmpty());
        assertEquals(0, queued());
    }

    // Apenas um remetente obtém a reserva enquanto ela não expira.
    @Test
    void concurrentClaimsHaveOneOwner() throws Exception {
        service.replaceRequest(request());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Optional<EmailChangeMailDto>> claim = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mails.claim(now, Duration.ofMinutes(1));
            };
            var first = pool.submit(claim); var second = pool.submit(claim); start.countDown();
            assertEquals(1, java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .filter(Optional::isPresent).count());
        }
    }

    // Uma reserva abandonada pode ser retomada; o remetente antigo perde a autorização de concluir.
    @Test
    void abandonedClaimAndFailedDeliveryCanBeRetried() {
        service.replaceRequest(request());
        var first = mails.claim(now, Duration.ofMinutes(1)).orElseThrow();
        var second = mails.claim(now.plusMinutes(1), Duration.ofMinutes(1)).orElseThrow();
        assertEquals(first.id(), second.id()); assertEquals(2, second.attempts());
        assertFalse(mails.markDelivered(first, now));
        assertFalse(mails.retryLater(first, now));
        assertTrue(mails.retryLater(second, now.plusMinutes(3)));
        assertTrue(mails.claim(now.plusMinutes(2), Duration.ofMinutes(1)).isEmpty());
        var third = mails.claim(now.plusMinutes(3), Duration.ofMinutes(1)).orElseThrow();
        assertEquals(3, third.attempts());
        assertTrue(mails.markDelivered(third, now.plusMinutes(3)));
        assertFalse(mails.markDelivered(third, now.plusMinutes(3)));
        assertTrue(mails.claim(now.plusMinutes(5), Duration.ofMinutes(1)).isEmpty());
    }

    private EmailChangeRequestDto request() {
        return new EmailChangeRequestDto(UUID.randomUUID(), userId, "new@example.com", now, now.plusMinutes(15), null);
    }
    private int queued() { return jdbc.queryForObject("SELECT count(*) FROM email_change_mail_outbox", Integer.class); }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

// Serviços, JWT e transações JDBC/JPA reais; renderização de e-mail e transporte SMTP simulados.
@EnabledIfSystemProperty(named = "sonarfy.test.deactivation-postgres", matches = "true")
@SpringJUnitConfig(AccountDeactivationInvalidationPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=deactivation-invalidation-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class AccountDeactivationInvalidationPostgresTest {
    @Configuration
    @Import({AccountReactivationService.class, ReactivationMailService.class,
            EmailChangeConfirmationService.class, EmailChangeRequestRepository.class, EmailChangeMailRepository.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override DataSource dataSource(org.springframework.core.env.Environment env) {
            return new AccountDeactivationPersistencePostgresTest.Config().dataSource();
        }
        @Bean @Override LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean @Override AccountDeactivationRequestRepository deactivations(DataSource ds) {
            return new AccountDeactivationRequestRepository(jdbc(ds));
        }
        @Bean org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator() {
            return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        }
    }
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired PasswordEncoder passwords;
    @Autowired UserService credentials;
    @Autowired AccountReactivationService reactivation;
    @Autowired EmailChangeConfirmationService emailConfirmation;
    @Autowired EmailChangeRequestRepository emailRequests;
    @Autowired AccountDeactivationRequestRepository requests;
    @Autowired JwtService jwt;
    @Autowired RefreshTokenService refresh;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    User actor, other;
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    AccountDeactivationRequestRepository.Issued issue(User user) {
        return tx().execute(status -> requests.replace(user.getId()));
    }
    Optional<Long> consume(String token) { return tx().execute(status -> requests.consume(token)); }

    @BeforeEach void fixture() {
        users.deleteAll(); actor = account("invalidation-a"); other = account("invalidation-b");
        db.update("UPDATE users SET password_updated_at = statement_timestamp() - interval '1 minute'");
        refresh.issue(actor);
    }
    User account(String handle) {
        User user = new User(); user.setHandle(handle); user.setEmail(handle + "@example.com");
        user.setName("Conta"); user.setSurname("Teste"); user.setDisplayName("Conta Teste");
        user.setCity("Campinas"); user.setCountry("Brasil"); user.setBirthDate(LocalDate.of(1990, 1, 1));
        user.setPassword(passwords.encode("Current123!")); user.setVerified(true);
        user.setPermission(permissions.findByName("ROLE_USER").orElseThrow());
        return users.saveAndFlush(user);
    }

    // Cada alteração confirmada invalida somente a autorização da conta alterada.
    @ParameterizedTest @ValueSource(strings = {"password", "reset", "email", "pending-email", "reactivation"})
    void invalidatesPreviousAuthorization(String change) {
        prepare(change);
        var original = issue(actor); var foreign = issue(other);
        change(change);
        assertTrue(consume(original.token()).isEmpty());
        assertEquals(Optional.of(other.getId()), consume(foreign.token()));
        assertEquals(Optional.of(actor.getId()), consume(issue(actor).token()));
    }

    // Rollback restaura conjuntamente credenciais, autorização e efeitos da transição.
    @ParameterizedTest @ValueSource(strings = {"password", "reset", "email", "pending-email", "reactivation"})
    void rollbackPreservesAuthorizationAndAccount(String change) {
        prepare(change);
        var original = issue(actor); var before = users.findById(actor.getId()).orElseThrow();
        tx().executeWithoutResult(status -> { change(change); users.flush(); status.setRollbackOnly(); });
        var after = users.findById(actor.getId()).orElseThrow();
        assertEquals(before.getEmail(), after.getEmail()); assertEquals(before.getPassword(), after.getPassword());
        assertEquals(before.isActive(), after.isActive());
        assertEquals(before.getAccessTokenVersion(), after.getAccessTokenVersion());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM reactivation_mail_outbox", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, actor.getId()));
        assertEquals(Optional.of(actor.getId()), consume(original.token()));
    }

    // Um consumo já iniciado relê a autorização depois da mudança que detém o bloqueio da conta.
    @ParameterizedTest @ValueSource(strings = {"password", "reset", "email", "pending-email", "reactivation"})
    void waitingConsumptionCannotAuthorizeAfterChange(String change) throws Exception {
        prepare(change);
        var original = issue(actor);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = tx().execute(status -> {
                db.queryForObject("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, actor.getId());
                var future = pool.submit(() -> consume(original.token()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    db.execute("SELECT pg_stat_clear_snapshot()");
                    if (db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class) > 0) break;
                    assertTrue(System.nanoTime() < deadline);
                    try { Thread.sleep(20); } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                }
                change(change);
                return future;
            });
            assertTrue(waiting.get(5, TimeUnit.SECONDS).isEmpty());
        }
    }

    // Credenciais recusadas e reativação de conta já ativa não consomem autorização vigente.
    @Test void rejectedCredentialsAndActiveReactivationPreserveAuthorization() {
        var original = issue(actor);
        assertThrows(ResponseStatusException.class, () -> credentials.changePassword(actor.getId(),
                new ChangePasswordDto(jwt.generatePasswordChangeToken(actor.getId().toString()),
                        "Wrong123!", "NextPassword123!", "NextPassword123!")));
        assertThrows(ResponseStatusException.class, () -> reactivation.reactivate(new LoginDto(actor.getHandle(), "Wrong123!")));
        reactivation.reactivate(new LoginDto(actor.getHandle(), "Current123!"));
        assertEquals(Optional.of(actor.getId()), consume(original.token()));
    }

    private void prepare(String change) {
        if (change.equals("pending-email")) db.update("UPDATE users SET verified = false WHERE user_id = ?", actor.getId());
        if (change.equals("reactivation")) db.update("UPDATE users SET active = false WHERE user_id = ?", actor.getId());
    }

    private void change(String change) {
        switch (change) {
            case "password" -> credentials.changePassword(actor.getId(), new ChangePasswordDto(
                    jwt.generatePasswordChangeToken(actor.getId().toString()), "Current123!", "NextPassword123!", "NextPassword123!"));
            case "reset" -> credentials.resetPassword(new ResetPasswordDto(
                    jwt.generatePasswordResetToken(actor.getId().toString()), "NextPassword123!", "NextPassword123!"));
            case "pending-email" -> credentials.updatePendingEmail(actor.getId(), "next@example.com");
            case "reactivation" -> reactivation.reactivate(new LoginDto(actor.getHandle(), "Current123!"));
            case "email" -> {
                var now = OffsetDateTime.now().minusSeconds(1);
                var request = new EmailChangeRequestDto(UUID.randomUUID(), actor.getId(), "next@example.com", now, now.plusMinutes(15), null);
                tx().executeWithoutResult(status -> emailRequests.replaceRequest(request));
                emailConfirmation.confirm(jwt.generateEmailChangeToken(request));
            }
            default -> throw new IllegalArgumentException(change);
        }
    }
}

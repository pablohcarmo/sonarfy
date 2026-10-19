package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.events.ActivationEmailListener;
import br.com.pablohcarmo.sonarfy.events.ActivationEmailRequested;
import br.com.pablohcarmo.sonarfy.exceptions.EmailDeliveryException;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.exceptions.TemplateInputException;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real Spring proxies, JPA and database transactions; email rendering and transport are mocked.
 * Defaults to an isolated H2 database. For PostgreSQL, supply sonarfy.test.jdbc-url,
 * sonarfy.test.db-user and sonarfy.test.db-password targeting a disposable database:
 * the mapped tables are created and dropped by this context.
 */
@SpringJUnitConfig(EmailReservationIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "app.frontend-url=http://localhost:3000",
        "api.security.token.secret=isolated-test-secret",
        "spring.mail.username=noreply@example.com"
})
class EmailReservationIntegrationTest {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import({EmailCooldownService.class, AuthEmailService.class, UserService.class,
            ActivationEmailListener.class})
    static class Config {
        @Bean DataSource dataSource(Environment env) {
            return new DriverManagerDataSource(
                    env.getProperty("sonarfy.test.jdbc-url",
                            "jdbc:h2:mem:email_reservation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                    env.getProperty("sonarfy.test.db-user", "sa"),
                    env.getProperty("sonarfy.test.db-password", ""));
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("br.com.pablohcarmo.sonarfy.entities");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean EmailService emailService() { return mock(EmailService.class); }
        @Bean EmailTemplateService emailTemplateService() { return mock(EmailTemplateService.class); }
        @Bean JavaMailSender javaMailSender() { return mock(JavaMailSender.class); }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
        @Bean JwtService jwtService() {
            JwtService jwt = new JwtService();
            ReflectionTestUtils.setField(jwt, "secretKey", "isolated-test-secret");
            return jwt;
        }
    }

    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired EmailCooldownService cooldown;
    @Autowired AuthEmailService authEmails;
    @Autowired EmailService transport;
    @Autowired EmailTemplateService templates;
    @Autowired UserService userService;
    @Autowired JwtService jwt;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    private Permission permission;

    @BeforeEach
    void prepare() {
        reset(transport, templates);
        when(templates.activation(any(User.class), anyString())).thenAnswer(call ->
                new EmailContent("activation-html", call.getArgument(1), Map.of()));
        when(templates.activationResend(any(User.class), anyString())).thenAnswer(call ->
                new EmailContent("resend-html", call.getArgument(1), Map.of()));
        users.deleteAll();
        permission = permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission role = new Permission();
            role.setName("ROLE_USER");
            return permissions.save(role);
        });
    }

    @Test
    void simultaneousRequestsProduceOneReservationAndOneDelivery() throws Exception {
        User user = persistedUser(true);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> request = () -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    authEmails.sendPasswordResetEmail(user, "reset-token");
                    return true;
                } catch (ResponseStatusException rejected) {
                    assertEquals(429, rejected.getStatusCode().value());
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(request);
            Future<Boolean> second = pool.submit(request);
            int accepted = (first.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, accepted);
        }
        verify(transport, times(1)).sendEmail(eq(user.getEmail()), anyString(), anyString());
        assertNotNull(users.findById(user.getId()).orElseThrow().getLastEmailSentAt());
    }

    @Test
    void deliveryFailureKeepsCommittedReservationAndBlocksRetry() {
        User user = persistedUser(true);
        when(transport.sendEmail(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            // Fresh repository transaction sees the reservation before SMTP.
            assertNotNull(users.findById(user.getId()).orElseThrow().getLastEmailSentAt());
            throw new EmailDeliveryException("SMTP offline", new IllegalStateException());
        });
        assertThrows(EmailDeliveryException.class,
                () -> authEmails.sendPasswordResetEmail(user, "reset-token"));
        assertEquals(429, assertThrows(ResponseStatusException.class,
                () -> authEmails.sendPasswordResetEmail(user, "retry")).getStatusCode().value());
        verify(transport, times(1)).sendEmail(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renderingFailureDoesNotReserveAndAllowsImmediateRetry(boolean resend) {
        User user = persistedUser(false);
        TemplateInputException failure = new TemplateInputException("Template unavailable");
        EmailContent rendered = new EmailContent("html", "text", Map.of());
        if (resend) {
            when(templates.activationResend(any(User.class), anyString()))
                    .thenThrow(failure).thenReturn(rendered);
        } else {
            when(templates.activation(any(User.class), anyString()))
                    .thenThrow(failure).thenReturn(rendered);
        }
        Runnable request = resend
                ? () -> authEmails.resendActivationEmail(user.getEmail())
                : () -> authEmails.sendActivationEmail(user, "confirmation");

        assertSame(failure, assertThrows(TemplateInputException.class, request::run));
        assertNull(users.findById(user.getId()).orElseThrow().getLastEmailSentAt());
        verifyNoInteractions(transport);

        assertDoesNotThrow(request::run);
        assertNotNull(users.findById(user.getId()).orElseThrow().getLastEmailSentAt());
        verify(transport).sendHtmlEmail(eq(user.getEmail()), anyString(), same(rendered));
    }

    @Test
    void simultaneousActivationRequestsProduceOneReservationAndOneDelivery() throws Exception {
        User user = persistedUser(false);
        CyclicBarrier renderedTogether = new CyclicBarrier(2);
        when(templates.activation(any(User.class), anyString())).thenAnswer(call -> {
            renderedTogether.await(10, TimeUnit.SECONDS);
            return new EmailContent("html", "text", Map.of());
        });
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> request = () -> {
                try {
                    authEmails.sendActivationEmail(user, "confirmation");
                    return true;
                } catch (ResponseStatusException rejected) {
                    assertEquals(429, rejected.getStatusCode().value());
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(request);
            Future<Boolean> second = pool.submit(request);
            int accepted = (first.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, accepted);
        }
        verify(transport).sendHtmlEmail(eq(user.getEmail()), anyString(), any(EmailContent.class));
        assertNotNull(users.findById(user.getId()).orElseThrow().getLastEmailSentAt());
    }

    @Test
    void expiredReservationAllowsAnotherDelivery() {
        User user = persistedUser(true);
        authEmails.sendPasswordResetEmail(user, "first");
        new JdbcTemplate(dataSource).update(
                "UPDATE users SET last_email_sent_at = ? WHERE user_id = ?",
                OffsetDateTime.now().minusMinutes(1), user.getId());
        authEmails.sendPasswordResetEmail(user, "second");
        verify(transport, times(2)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void staleProfileSaveCannotEraseReservation() {
        User stale = persistedUser(true);
        cooldown.reserveOrThrow(stale.getId(), stale.getEmail(), true);
        OffsetDateTime reserved = users.findById(stale.getId()).orElseThrow().getLastEmailSentAt();
        stale.setName("Updated profile");
        users.saveAndFlush(stale);
        User updated = users.findById(stale.getId()).orElseThrow();
        assertEquals("Updated profile", updated.getName());
        assertEquals(reserved.toInstant(), updated.getLastEmailSentAt().toInstant());
    }

    @Test
    void concurrentProfileTransactionCannotEraseReservation() throws Exception {
        User user = persistedUser(true);
        CountDownLatch profileLoaded = new CountDownLatch(1);
        CountDownLatch reservationCommitted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> profile = pool.submit(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> {
                        User old = users.findById(user.getId()).orElseThrow();
                        profileLoaded.countDown();
                        try {
                            assertTrue(reservationCommitted.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        old.setName("Concurrent update");
                        users.saveAndFlush(old);
                    }));
            assertTrue(profileLoaded.await(10, TimeUnit.SECONDS));
            try {
                cooldown.reserveOrThrow(user.getId(), user.getEmail(), true);
            } finally {
                reservationCommitted.countDown();
            }
            profile.get(15, TimeUnit.SECONDS);
        }
        User updated = users.findById(user.getId()).orElseThrow();
        assertEquals("Concurrent update", updated.getName());
        assertNotNull(updated.getLastEmailSentAt());
        assertThrows(ResponseStatusException.class,
                () -> cooldown.reserveOrThrow(user.getId(), user.getEmail(), true));
    }

    @Test
    void reservationRejectsChangedIdentityOrVerification() {
        User user = persistedUser(true);
        assertThrows(ResponseStatusException.class,
                () -> cooldown.reserveOrThrow(user.getId(), "old@example.com", true));
        assertThrows(ResponseStatusException.class,
                () -> cooldown.reserveOrThrow(user.getId(), user.getEmail(), false));
        assertNull(users.findById(user.getId()).orElseThrow().getLastEmailSentAt());
    }

    @Test
    void activationAndRecoveryShareCooldown() {
        User user = persistedUser(false);
        authEmails.sendActivationEmail(user, "activation");
        user.setVerified(true);
        users.saveAndFlush(user);
        assertThrows(ResponseStatusException.class,
                () -> authEmails.sendPasswordResetEmail(user, "reset"));
        verify(transport, times(1)).sendHtmlEmail(anyString(), anyString(), any(EmailContent.class));
    }

    @Test
    void rollbackDoesNotSendActivation() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            User user = persistedUser(false);
            publisher.publishEvent(new ActivationEmailRequested(user.getId()));
            verifyNoInteractions(transport);
            status.setRollbackOnly();
        });
        verifyNoInteractions(transport);
        assertEquals(0, users.count());
    }

    @Test
    void registrationCommitsBeforeSendingAndUsesValidConfirmationToken() {
        when(transport.sendHtmlEmail(anyString(), anyString(), any(EmailContent.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            User saved = users.findByEmailIgnoreCase(call.getArgument(0)).orElseThrow();
            assertNotNull(saved.getLastEmailSentAt());
            String body = call.getArgument(2, EmailContent.class).textBody();
            String token = body.substring(body.indexOf("?token=") + 7).split("\\s")[0];
            assertEquals(saved.getId().toString(), jwt.validateTokenAndGetEmail(token));
            return "accepted";
        });
        var response = userService.newUser(registration());
        assertNotNull(jwt.validateRegistrationTokenAndGetUserId(response.registrationToken()));
        verify(transport).sendHtmlEmail(eq("new@example.com"), anyString(), any(EmailContent.class));
    }

    @Test
    void failedActivationDoesNotUndoRegistration() {
        when(transport.sendHtmlEmail(anyString(), anyString(), any(EmailContent.class)))
                .thenThrow(new EmailDeliveryException("SMTP offline", new IllegalStateException()));
        assertDoesNotThrow(() -> userService.newUser(registration()));
        User saved = users.findByEmailIgnoreCase("new@example.com").orElseThrow();
        assertNotNull(saved.getLastEmailSentAt());
        assertEquals(429, assertThrows(ResponseStatusException.class,
                () -> authEmails.resendActivationEmail(saved.getEmail())).getStatusCode().value());
        verify(transport).sendHtmlEmail(eq("new@example.com"), anyString(), any(EmailContent.class));
    }

    @Test
    void renderingFailureAfterCommitKeepsRegistrationAndAllowsImmediateResend() {
        when(templates.activation(any(User.class), anyString()))
                .thenThrow(new TemplateInputException("Template unavailable"));

        var response = assertDoesNotThrow(() -> userService.newUser(registration()));
        User saved = users.findByEmailIgnoreCase("new@example.com").orElseThrow();
        assertNotNull(jwt.validateRegistrationTokenAndGetUserId(response.registrationToken()));
        assertNull(saved.getLastEmailSentAt());
        verifyNoInteractions(transport);

        assertDoesNotThrow(() -> authEmails.resendActivationEmail(saved.getEmail()));
        assertNotNull(users.findById(saved.getId()).orElseThrow().getLastEmailSentAt());
        verify(transport).sendHtmlEmail(eq(saved.getEmail()),
                eq("Seu novo link de ativação no Sonarfy"), any(EmailContent.class));
    }

    @Test
    void pendingEmailChangeIsCommittedBeforeDelivery() {
        User user = persistedUser(false);
        when(transport.sendHtmlEmail(anyString(), anyString(), any(EmailContent.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("changed@example.com", users.findById(user.getId()).orElseThrow().getEmail());
            return "accepted";
        });
        userService.updatePendingEmail(user.getId(), "changed@example.com");
        verify(transport).sendHtmlEmail(eq("changed@example.com"), anyString(), any(EmailContent.class));
    }

    private User persistedUser(boolean verified) {
        User user = new User();
        user.setPermissionId(permission);
        user.setName("Test");
        user.setSurname("Account");
        user.setHandle("test-account");
        user.setEmail("user@example.com");
        user.setVerified(verified);
        return users.saveAndFlush(user);
    }

    private NewUserDto registration() {
        NewUserDto dto = new NewUserDto();
        dto.setName("New");
        dto.setSurname("Account");
        dto.setEmail("new@example.com");
        dto.setHandle("new-account");
        dto.setPassword("TestPassword123!");
        dto.setBirthDate(LocalDate.now().minusYears(20));
        return dto;
    }
}

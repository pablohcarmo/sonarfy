package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.*;
import br.com.pablohcarmo.sonarfy.controllers.EmailChangeController;
import br.com.pablohcarmo.sonarfy.dto.EmailChangeRequestDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.*;
import com.auth0.jwt.JWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// PostgreSQL, HTTP, segurança e JpaTransactionManager reais; SMTP simulado.
@EnabledIfSystemProperty(named = "sonarfy.test.email-change-postgres", matches = "true")
@SpringJUnitWebConfig(EmailChangeRequestPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=email-change-test-secret",
        "app.email-change.enabled=true", "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class EmailChangeRequestPostgresTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, EmailChangeController.class, EmailChangeRequestService.class,
            EmailChangeService.class, EmailChangeRequestRepository.class, EmailChangeMailRepository.class, EmailChangeDispatcher.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override DataSource dataSource(Environment env) {
            return new EmailChangePersistencePostgresTest.Config().dataSource();
        }
        @Bean @Override LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired RefreshTokenRepository sessions;
    @Autowired RefreshTokenService refresh;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired EmailService email;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailChangeService changes;
    @Autowired EmailChangeRequestRepository requests;
    @MockitoSpyBean EmailChangeMailRepository mails;
    @Autowired EmailChangeDispatcher dispatcher;
    private MockMvc mvc;
    private User user;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void fixture() {
        sessions.deleteAll(); users.deleteAll(); reset(email);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = users.saveAndFlush(new User(null, permissions.findByName("ROLE_USER").orElseThrow(),
                "Email", "Test", "Chosen profile", "email-test", "original@example.com", passwords.encode("Request123!"),
                true, true, "Brasilia", "Brasil", null, null, null, LocalDate.of(2000, 1, 1), null, null, null, null));
    }

    @AfterEach
    void cleanup() { sessions.deleteAll(); users.deleteAll(); }

    // A resposta não entrega a autorização; somente o destinatário novo recebe o link.
    @Test
    void acceptsAndDispatchesPurposeSpecificAuthorizationWithoutChangingAccountOrSession() throws Exception {
        refresh.issue(user);
        var input = payload(); input.put("userId", "999999");
        request(input).andExpect(status().isAccepted()).andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.token").doesNotExist());
        assertEquals(0, mockingDetails(email).getInvocations().size());
        var stored = pending();
        assertEquals("next@example.com", stored.newEmail());
        assertEquals(900, Duration.between(stored.issuedAt(), stored.expiresAt()).getSeconds());
        assertEquals("original@example.com", users.findById(user.getId()).orElseThrow().getEmail());
        assertEquals(1, sessions.countByUserId(user.getId()));
        assertTrue(dispatcher.dispatchOne());
        var sent = mockingDetails(email).getInvocations();
        assertEquals(1, sent.size());
        var arguments = sent.iterator().next().getArguments();
        assertEquals("next@example.com", arguments[0]);
        String token = ((String) arguments[2]).split("#token=")[1];
        var decoded = JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256("email-change-test-secret"))
                .withIssuer("sonarfy-api").withClaim("purpose", "email_change").build().verify(token);
        assertEquals(stored.id().toString(), decoded.getId());
        assertEquals(user.getId().toString(), decoded.getSubject());
        assertEquals(stored.expiresAt().toInstant(), decoded.getExpiresAt().toInstant());
        assertNull(jwt.validateAccessTokenAndGetUserId(token));
        assertNull(jwt.validatePasswordChangeTokenAndGetUserId(token));
        assertNull(jwt.validatePasswordResetTokenAndGetUserId(token));
        assertNull(jwt.validateRegistrationTokenAndGetUserId(token));
        assertNull(jwt.validateTokenAndGetEmail(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "long", "missing-email", "missing-password", "blank-email", "blank-password",
            "null-email", "null-password", "wrong-password", "same", "occupied", "bytes"})
    void rejectsInvalidRequestsWithoutPendingChanges(String scenario) throws Exception {
        var input = payload();
        int status = 400;
        switch (scenario) {
            case "email" -> input.put("newEmail", "invalid");
            case "long" -> input.put("newEmail", "a".repeat(145) + "@example.com");
            case "missing-email" -> input.remove("newEmail");
            case "missing-password" -> input.remove("currentPassword");
            case "blank-email" -> input.put("newEmail", " ");
            case "blank-password" -> input.put("currentPassword", " ");
            case "null-email" -> input.put("newEmail", null);
            case "null-password" -> input.put("currentPassword", null);
            case "wrong-password" -> { input.put("currentPassword", "wrong"); status = 401; }
            case "bytes" -> { input.put("currentPassword", "é".repeat(40)); status = 401; }
            case "same" -> input.put("newEmail", "ORIGINAL@EXAMPLE.COM");
            case "occupied" -> { input.put("newEmail", "OCCUPIED@EXAMPLE.COM");
                jdbc.update("""
                        INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash, city, country, birth_date)
                        SELECT permission_id, name, surname, display_name, 'occupied', 'occupied@example.com', password_hash, city, country, birth_date
                        FROM users WHERE user_id = ?
                        """, user.getId()); status = 409; }
        }
        request(input).andExpect(status().is(status));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_change_requests", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "password_change", "email_change", "inactive", "unverified"})
    void requiresRealAccessAuthentication(String scenario) throws Exception {
        String token = switch (scenario) {
            case "missing" -> "";
            case "invalid" -> "Bearer invalid";
            case "password_change" -> "Bearer " + jwt.generatePasswordChangeToken(user.getId().toString());
            case "email_change" -> "Bearer " + jwt.generateEmailChangeToken(new EmailChangeRequestDto(UUID.randomUUID(),
                    user.getId(), "next@example.com", OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(15), null));
            default -> bearer();
        };
        if (scenario.equals("inactive")) jdbc.update("UPDATE users SET active = false WHERE user_id = ?", user.getId());
        if (scenario.equals("unverified")) jdbc.update("UPDATE users SET verified = false WHERE user_id = ?", user.getId());
        mvc.perform(post("/api/users/me/email-change-request").header("Authorization", token)
                .contentType("application/json").content(json.writeValueAsString(payload()))).andExpect(status().isUnauthorized());
    }

    @Test
    void cooldownAndReplacementPreserveOnlyLatestAuthorization() throws Exception {
        request(payload()).andExpect(status().isAccepted()); var first = pending();
        request(payload()).andExpect(status().isTooManyRequests()); assertEquals(first.id(), pending().id());
        jdbc.update("UPDATE users SET last_email_sent_at = NULL WHERE user_id = ?", user.getId());
        request(payload()).andExpect(status().isAccepted());
        assertNotEquals(first.id(), pending().id()); assertTrue(requests.findRequest(first.id()).isEmpty());
    }

    // Uma falha após o upsert JDBC desfaz a solicitação na transação gerenciada por JPA.
    @Test
    void queueFailureRollsBackRequest() {
        doThrow(new IllegalStateException("test enqueue failure")).when(mails).enqueue(any(), anyLong(), anyString(), anyString(), any());
        assertThrows(jakarta.servlet.ServletException.class, () -> request(payload()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_change_requests", Integer.class));
    }

    @Test
    void concurrentRequestsRespectSharedCooldown() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> submit = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return request(payload()).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(submit); var second = pool.submit(submit); start.countDown();
            assertEquals(Set.of(202, 429), Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_change_mail_outbox", Integer.class));
    }

    @Test
    void incompleteFlowIsDisabledByDefault() {
        try (var disabled = new AnnotationConfigApplicationContext()) {
            disabled.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "disabled", Map.of("app.email-change.enabled", "false")));
            disabled.register(EmailChangeController.class, EmailChangeDispatcher.class, EmailChangeSchedulingConfig.class);
            disabled.refresh();
            assertTrue(disabled.getBeansOfType(EmailChangeController.class).isEmpty());
            assertTrue(disabled.getBeansOfType(EmailChangeDispatcher.class).isEmpty());
        }
    }

    @Test
    void failedSmtpIsRetriedWithoutExtendingExpiry() throws Exception {
        request(payload()).andExpect(status().isAccepted()); var stored = pending();
        when(email.sendEmail(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("test SMTP failure")).thenReturn("sent");
        dispatcher.dispatchOne();
        assertFalse(dispatcher.dispatchOne());
        jdbc.update("UPDATE email_change_mail_outbox SET available_at = CURRENT_TIMESTAMP");
        assertTrue(dispatcher.dispatchOne());
        assertEquals(stored.expiresAt().toInstant(), pending().expiresAt().toInstant());
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM email_change_mail_outbox", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "consumed", "replaced"})
    void obsoleteConfirmationsAreDiscarded(String scenario) {
        OffsetDateTime now = OffsetDateTime.now();
        var old = new EmailChangeRequestDto(UUID.randomUUID(), user.getId(), "old@example.com", now.minusMinutes(20),
                scenario.equals("expired") ? now.minusMinutes(5) : now.plusMinutes(15), null);
        changes.replaceRequest(old);
        if (scenario.equals("consumed")) requests.consume(old.id(), now);
        if (scenario.equals("replaced")) changes.replaceRequest(new EmailChangeRequestDto(UUID.randomUUID(), user.getId(),
                "next@example.com", now, now.plusMinutes(15), null));
        assertTrue(dispatcher.dispatchOne()); assertEquals(0, mockingDetails(email).getInvocations().size());
    }

    private ResultActions request(Map<String, String> input) throws Exception {
        return mvc.perform(post("/api/users/me/email-change-request").header("Authorization", bearer())
                .contentType("application/json").content(json.writeValueAsString(input)));
    }
    private String bearer() { return "Bearer " + jwt.generateAccessToken(user.getId().toString()); }
    private Map<String, String> payload() { return new HashMap<>(Map.of("newEmail", "Next@Example.COM", "currentPassword", "Request123!")); }
    private EmailChangeRequestDto pending() {
        return requests.findRequest(jdbc.queryForObject("SELECT request_id FROM email_change_requests WHERE user_id = ?", UUID.class, user.getId())).orElseThrow();
    }
}

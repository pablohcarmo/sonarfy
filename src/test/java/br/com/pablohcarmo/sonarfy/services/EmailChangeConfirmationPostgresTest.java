package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.controllers.*;
import br.com.pablohcarmo.sonarfy.dto.EmailChangeRequestDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.*;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Confirmação HTTP e transações JDBC/JPA reais em PostgreSQL descartável; SMTP simulado.
@EnabledIfSystemProperty(named = "sonarfy.test.email-change-postgres", matches = "true")
@SpringJUnitWebConfig(EmailChangeConfirmationPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=email-change-test-secret", "app.email-change.enabled=true",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class EmailChangeConfirmationPostgresTest {
    @Configuration
    @Import({EmailChangeConfirmationController.class, EmailChangeConfirmationService.class,
            AuthController.class, AuthService.class, UserController.class, ProfileController.class, ProfileService.class})
    static class Config extends EmailChangeRequestPostgresTest.Config {
        @Bean org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator() {
            return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        }
    }
    @Autowired WebApplicationContext context;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean EmailChangeMailRepository mails;
    @Autowired PermissionRepository permissions;
    @Autowired RefreshTokenRepository sessions;
    @Autowired RefreshTokenService refresh;
    @Autowired EmailChangeRequestRepository requests;
    @Autowired EmailChangeService changes;
    @Autowired EmailChangeDispatcher dispatcher;
    @Autowired JwtService jwt;
    @Autowired EmailService email;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired UserService userService;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void fixture() {
        sessions.deleteAll(); users.deleteAll(); reset(email);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = account("original");
    }
    @AfterEach void cleanup() { sessions.deleteAll(); users.deleteAll(); }

    // Exercita solicitação, envio, confirmação, sessões, login e notificação ao endereço original.
    @Test
    void fullFlowChangesOnlyEmailAndRequiresNewSession() throws Exception {
        String oldRefresh = refresh.issue(user); refresh.issue(user, true);
        String oldAccess = jwt.generateAccessToken(user.getId().toString());
        mvc.perform(post("/api/users/me/email-change-request").header("Authorization", "Bearer " + oldAccess)
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "newEmail", "NEXT@EXAMPLE.COM", "currentPassword", "Request123!")))).andExpect(status().isAccepted());
        dispatcher.dispatchOne();
        var sent = mockingDetails(email).getInvocations(); assertEquals(1, sent.size());
        String token = ((String) sent.iterator().next().getArguments()[2]).split("#token=")[1];
        confirm(token).andExpect(status().isOk()).andExpect(jsonPath("$.requiresLogin").value(true))
                .andExpect(jsonPath("$.token").doesNotExist());
        var saved = users.findById(user.getId()).orElseThrow();
        assertEquals("next@example.com", saved.getEmail()); assertTrue(user.getPassword().equals(saved.getPassword()));
        assertEquals("Chosen profile", saved.getDisplayName()); assertTrue(saved.isVerified()); assertTrue(saved.isActive());
        assertEquals(0, sessions.countByUserId(user.getId()));
        assertNotNull(requests.findRequest(UUID.fromString(JWT.decode(token).getId())).orElseThrow().consumedAt());
        assertEquals(401, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> refresh.rotate(oldRefresh)).getStatusCode().value());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("next@example.com"));
        login("original@example.com").andExpect(status().isUnauthorized());
        login("next@example.com").andExpect(status().isOk());
        assertTrue(dispatcher.dispatchOne());
        var deliveries = mockingDetails(email).getInvocations().toArray(); assertEquals(2, deliveries.length);
        var notification = ((org.mockito.invocation.Invocation) deliveries[1]).getArguments();
        assertEquals("original@example.com", notification[0]); assertFalse(((String) notification[2]).contains("#token="));
        confirm(token).andExpect(status().isBadRequest()); assertEquals(1, notifications());
        assertEquals(1, sessions.countByUserId(user.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank", "malformed", "access", "password_reset", "password_change", "email_confirmation",
            "pending_registration", "expired", "no-exp", "no-iat", "no-id", "bad-id", "no-sub", "bad-sub", "tampered", "issuer", "oversized"})
    void rejectsInvalidOrWrongPurposeAuthorizations(String scenario) throws Exception {
        var pending = pending(user, "next@example.com");
        var builder = JWT.create().withIssuer("sonarfy-api").withSubject(user.getId().toString())
                .withJWTId(pending.id().toString()).withClaim("purpose", "email_change")
                .withIssuedAt(Instant.now().minusSeconds(1)).withExpiresAt(Instant.now().plusSeconds(900));
        switch (scenario) {
            case "expired" -> builder.withExpiresAt(Instant.now().minusSeconds(1));
            case "no-exp" -> builder.withExpiresAt((Instant) null);
            case "no-iat" -> builder.withIssuedAt((Instant) null);
            case "no-id" -> builder.withJWTId(null);
            case "bad-id" -> builder.withJWTId("invalid");
            case "no-sub" -> builder.withSubject(null);
            case "bad-sub" -> builder.withSubject("invalid");
            case "issuer" -> builder.withIssuer("other");
            default -> { if (scenario.contains("_") || scenario.equals("access")) builder.withClaim("purpose", scenario); }
        }
        String token = builder.sign(Algorithm.HMAC256(scenario.equals("tampered") ? "other-secret" : "email-change-test-secret"));
        if (scenario.equals("blank")) token = " "; if (scenario.equals("malformed")) token = "invalid";
        if (scenario.equals("oversized")) token = "x".repeat(4097);
        confirm(token).andExpect(status().isBadRequest()); assertEquals(0, notifications());
    }

    @ParameterizedTest
    @ValueSource(strings = {"replaced", "expired-row", "inactive", "unverified", "deleted", "wrong-owner", "format"})
    void checksCurrentRequestAndAccountStateAgain(String scenario) throws Exception {
        var pending = pending(user, scenario.equals("format") ? "invalid" : "next@example.com");
        String token = jwt.generateEmailChangeToken(pending);
        switch (scenario) {
            case "replaced" -> pending(user, "replacement@example.com");
            case "expired-row" -> jdbc.update("UPDATE email_change_requests SET expires_at = CURRENT_TIMESTAMP - interval '1 minute', issued_at = CURRENT_TIMESTAMP - interval '2 minutes'");
            case "inactive" -> jdbc.update("UPDATE users SET active = false WHERE user_id = ?", user.getId());
            case "unverified" -> jdbc.update("UPDATE users SET verified = false WHERE user_id = ?", user.getId());
            case "deleted" -> users.deleteById(user.getId());
            case "wrong-owner" -> token = jwt.generateEmailChangeToken(new EmailChangeRequestDto(pending.id(), account("other").getId(),
                    pending.newEmail(), pending.issuedAt(), pending.expiresAt(), null));
        }
        confirm(token).andExpect(status().isBadRequest()); assertEquals(0, notifications());
    }

    @Test
    void addressOccupiedAfterRequestReturnsConflictWithoutPartialWrites() throws Exception {
        var pending = pending(user, "next@example.com"); refresh.issue(user);
        var other = account("other");
        jdbc.update("UPDATE users SET email = 'NEXT@EXAMPLE.COM' WHERE user_id = ?", other.getId());
        confirm(jwt.generateEmailChangeToken(pending)).andExpect(status().isConflict());
        assertRolledBack(pending);
    }

    // Força falhas depois de operações reais: User, consumo JDBC, sessões e outbox devem reverter juntos.
    @ParameterizedTest
    @ValueSource(strings = {"notification", "other-unique", "check"})
    void transactionRollsBackAllFourEffects(String failure) {
        var pending = pending(user, "next@example.com"); refresh.issue(user);
        if (failure.equals("notification")) {
            doThrow(new IllegalStateException("test notification failure")).when(mails)
                    .enqueue(any(), anyLong(), eq("NOTIFY_OLD_EMAIL"), anyString(), any());
        } else {
            var violation = mock(org.hibernate.exception.ConstraintViolationException.class);
            when(violation.getSQLState()).thenReturn(failure.equals("check") ? "23514" : "23505");
            if (!failure.equals("check")) when(violation.getConstraintName()).thenReturn("uk_users_handle_ci");
            doThrow(new org.springframework.dao.DataIntegrityViolationException("test", violation)).when(users).saveAndFlush(any(User.class));
        }
        assertThrows(jakarta.servlet.ServletException.class, () -> confirm(jwt.generateEmailChangeToken(pending)));
        assertRolledBack(pending);
    }

    @Test
    void concurrentConfirmationOfSameRequestHasOneWinner() throws Exception {
        String token = jwt.generateEmailChangeToken(pending(user, "next@example.com"));
        assertEquals(Set.of(200, 400), race(token, token)); assertEquals(1, notifications());
    }

    @Test
    void confirmationAlsoRevokesSuccessorFromConcurrentRefresh() throws Exception {
        String raw = refresh.issue(user), token = jwt.generateEmailChangeToken(pending(user, "next@example.com"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var rotation = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { refresh.rotate(raw); }
                catch (org.springframework.web.server.ResponseStatusException failure) { assertEquals(401, failure.getStatusCode().value()); }
                return null;
            });
            var confirmation = pool.submit(() -> { start.await(); return confirm(token).andReturn().getResponse().getStatus(); });
            start.countDown(); rotation.get(15, TimeUnit.SECONDS); assertEquals(200, confirmation.get(15, TimeUnit.SECONDS));
        }
        assertEquals(0, sessions.countByUserId(user.getId()));
    }

    // A barreira força as duas consultas prévias a observarem ausência antes de disputar o índice real.
    @Test
    void concurrentAddressClaimsUseOnlyEmailUniqueConstraintAsConflict() throws Exception {
        var first = pending(user, "race@example.com"); refresh.issue(user);
        var other = account("other"); var second = pending(other, "RACE@EXAMPLE.COM"); refresh.issue(other);
        var ready = new CountDownLatch(2);
        doAnswer(call -> {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class,
                    call.getArgument(0, String.class)));
            ready.countDown(); assertTrue(ready.await(10, TimeUnit.SECONDS)); return Optional.empty();
        }).when(users).findByEmailIgnoreCase(argThat(value -> value.equalsIgnoreCase("race@example.com")));
        assertEquals(Set.of(200, 409), race(jwt.generateEmailChangeToken(first), jwt.generateEmailChangeToken(second)));
        assertEquals(1, notifications()); assertEquals(1, sessions.count());
        var loser = users.findAll().stream().filter(owner -> !owner.getEmail().equals("race@example.com")).findFirst().orElseThrow();
        assertNull(requests.findRequest(loser.getId().equals(user.getId()) ? first.id() : second.id()).orElseThrow().consumedAt());
    }

    @Test
    void notificationFailureDoesNotUndoConfirmationAndCanRetryAfterReplacement() throws Exception {
        var pending = pending(user, "next@example.com"); refresh.issue(user);
        confirm(jwt.generateEmailChangeToken(pending)).andExpect(status().isOk());
        dispatcher.dispatchOne();
        var now = OffsetDateTime.now().plusMinutes(1);
        changes.replaceRequest(new EmailChangeRequestDto(UUID.randomUUID(), user.getId(), "later@example.com", now, now.plusMinutes(15), null));
        when(email.sendEmail(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("test SMTP failure")).thenReturn("sent");
        dispatcher.dispatchOne();
        assertEquals("next@example.com", users.findById(user.getId()).orElseThrow().getEmail()); assertEquals(0, sessions.count());
        jdbc.update("UPDATE email_change_mail_outbox SET available_at = CURRENT_TIMESTAMP WHERE kind = 'NOTIFY_OLD_EMAIL'");
        dispatcher.dispatchOne();
        assertEquals(2, mockingDetails(email).getInvocations().size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM email_change_mail_outbox WHERE kind = 'NOTIFY_OLD_EMAIL' AND delivered_at IS NOT NULL", Integer.class));
    }

    // Um Principal capturado antes da troca continua ligado ao ID original, mesmo com o e-mail antigo reutilizado.
    @Test
    void staleAuthenticatedEmailCannotSelectAnotherAccount() throws Exception {
        var actor = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        confirm(jwt.generateEmailChangeToken(pending(user, "next@example.com"))).andExpect(status().isOk());
        var other = account("reclaimed");
        jdbc.update("UPDATE users SET email = 'original@example.com' WHERE user_id = ?", other.getId());
        mvc.perform(get("/api/users/me").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.email").value("next@example.com"));
        mvc.perform(get("/api/profile/me").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.identity.id").value(user.getId()))
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(put("/api/profile/me").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(actor))
                .contentType("application/json").content("{\"biography\":\"Updated profile\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
        mvc.perform(put("/api/users/me").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(actor))
                .contentType("application/json").content("""
                    {"name":"Stable","surname":"Actor","handle":"stable.actor","city":"Brasilia","country":"Brasil","birthDate":"2000-01-01"}
                    """)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("next@example.com"));
        assertNull(users.findById(other.getId()).orElseThrow().getBiography());
        jdbc.update("UPDATE users SET password_updated_at = CURRENT_TIMESTAMP - interval '1 minute'");
        String wrongOwner = jwt.generatePasswordChangeToken(other.getId().toString());
        var body = Map.of("token", wrongOwner, "currentPassword", "Request123!", "newPassword", "NewRequest123!", "confirmNewPassword", "NewRequest123!");
        mvc.perform(put("/api/users/me/password").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(actor))
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
    }

    // Uma edição iniciada antes da confirmação recarrega o estado protegido antes de salvar.
    @Test
    void profileUpdateCannotOverwriteEmailCommittedWhileItWasLoading() throws Exception {
        String token = jwt.generateEmailChangeToken(pending(user, "next@example.com"));
        var loaded = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            var cached = entityManager.find(User.class, user.getId());
            loaded.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return Optional.of(cached);
        }).when(users).findById(user.getId());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var editing = pool.submit(() -> userService.updateProfile(user.getId(),
                    new br.com.pablohcarmo.sonarfy.dto.UpdateProfileDto("Updated profile", null, null)));
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            try { confirm(token).andExpect(status().isOk()); } finally { release.countDown(); }
            assertEquals("next@example.com", editing.get(20, TimeUnit.SECONDS).email());
        }
        assertEquals("next@example.com", jdbc.queryForObject("SELECT email FROM users WHERE user_id = ?", String.class, user.getId()));
    }

    private void assertRolledBack(EmailChangeRequestDto pending) {
        assertEquals("original@example.com", users.findById(user.getId()).orElseThrow().getEmail());
        assertNull(requests.findRequest(pending.id()).orElseThrow().consumedAt());
        assertEquals(1, sessions.countByUserId(user.getId())); assertEquals(0, notifications());
    }
    private Set<Integer> race(String first, String second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return confirm(first).andReturn().getResponse().getStatus(); });
            var b = pool.submit(() -> { start.await(); return confirm(second).andReturn().getResponse().getStatus(); }); start.countDown();
            return Set.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
    private ResultActions confirm(String token) throws Exception {
        return mvc.perform(post("/api/auth/email-change-confirmation").header("Authorization", "Bearer ignored")
                .contentType("application/json").content(json.writeValueAsString(Map.of("token", token))));
    }
    private ResultActions login(String login) throws Exception {
        return mvc.perform(post("/api/auth/login").header("X-Sonarfy-CSRF", "1").contentType("application/json")
                .content(json.writeValueAsString(Map.of("login", login, "password", "Request123!"))));
    }
    private int notifications() { return jdbc.queryForObject("SELECT count(*) FROM email_change_mail_outbox WHERE kind = 'NOTIFY_OLD_EMAIL'", Integer.class); }
    private EmailChangeRequestDto pending(User owner, String target) {
        var now = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var pending = new EmailChangeRequestDto(UUID.randomUUID(), owner.getId(), target, now, now.plusMinutes(15), null);
        changes.replaceRequest(pending); return pending;
    }
    private User account(String handle) {
        return users.saveAndFlush(new User(null, permissions.findByName("ROLE_USER").orElseThrow(), "Email", "Test", "Chosen profile",
                handle, handle + "@example.com", passwords.encode("Request123!"), true, true, "Brasilia", "Brasil",
                null, null, null, LocalDate.of(2000, 1, 1), null, null, null, null));
    }
}

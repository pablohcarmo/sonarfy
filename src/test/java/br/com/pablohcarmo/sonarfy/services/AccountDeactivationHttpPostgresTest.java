package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.controllers.*;
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
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// HTTP, segurança e PostgreSQL reais; somente o transporte de e-mail é simulado nesta classe.
@EnabledIfSystemProperty(named = "sonarfy.test.deactivation-postgres", matches = "true")
@SpringJUnitWebConfig(AccountDeactivationHttpPostgresTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=deactivation-http-test-secret", "app.account-deactivation.enabled=true",
        "app.frontend-url=http://localhost:3000/", "spring.mail.username=noreply@example.com"})
class AccountDeactivationHttpPostgresTest {
    @Configuration @Import({AccountDeactivationController.class, AccountDeactivationDispatcher.class, AuthController.class})
    static class Config extends AccountDeactivationConfirmationPostgresTest.Config {}
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired RefreshTokenService refresh;
    @Autowired AccountDeactivationMailRepository mails;
    @Autowired AccountDeactivationRequestRepository requests;
    @Autowired AccountDeactivationDispatcher dispatcher;
    @Autowired EmailService email;
    @Autowired PlatformTransactionManager manager;
    MockMvc mvc;
    long actor;
    String access, session;
    final List<String> delivered = new ArrayList<>();
    @BeforeEach void fixture() {
        users.deleteAll(); reset(email); delivered.clear();
        actor = db.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Conta', 'Teste', 'Conta Teste', 'http-test', 'http-test@example.com', ?, 'Campinas', 'Brasil', '1990-01-01', true)
                RETURNING user_id
                """, Long.class, passwords.encode("Current123!"));
        access = jwt.generateAccessToken(Long.toString(actor)); session = refresh.issue(users.findById(actor).orElseThrow());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(email.sendEmail(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("http-test@example.com", call.getArgument(0));
            delivered.add(call.getArgument(2)); return "sent";
        });
    }
    @AfterEach void cleanup() { users.deleteAll(); }
    ResultActions request(String bearer, String body) throws Exception {
        var input = post("/api/users/me/account-deactivation-request").contentType("application/json").content(body);
        if (bearer != null) input.header("Authorization", "Bearer " + bearer);
        return mvc.perform(input).andExpect(header().string("Cache-Control", "no-store"));
    }
    ResultActions confirm(String token) throws Exception {
        return mvc.perform(post("/api/auth/account-deactivation-confirmation").header("Authorization", "invalid ignored header")
                .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    String deliveredToken(int index) { return delivered.get(index).split("#token=")[1]; }

    // Erro de desserialização não deve registrar o segredo no tratamento padrão de binding.
    @Test void malformedJsonDoesNotLogSubmittedToken() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        String secret = "deactivation." + "A".repeat(43);
        try {
            mvc.perform(post("/api/auth/account-deactivation-confirmation").contentType("application/json")
                    .content("\"" + secret + "\"")).andExpect(status().isBadRequest()).andExpect(content().string(""));
            assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains(secret)));
        } finally { logger.detachAppender(events); events.stop(); }
    }

    // Formulários simples e texto não substituem o contrato JSON das operações sensíveis.
    @ParameterizedTest @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded"})
    void rejectsSimpleFormContentTypes(String type) throws Exception {
        for (String route : List.of("/api/users/me/account-deactivation-request", "/api/auth/account-deactivation-confirmation"))
            mvc.perform(post(route).header("Authorization", "Bearer " + access).contentType(type).content("token=invalid"))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(header().string("Cache-Control", "no-store"));
        assertTrue(users.findById(actor).orElseThrow().isActive());
    }

    // Rejeição CORS também é não armazenável e não cria uma solicitação.
    @Test void rejectsUntrustedBrowserOriginWithoutCaching() throws Exception {
        for (String route : List.of("/api/users/me/account-deactivation-request", "/api/auth/account-deactivation-confirmation"))
            mvc.perform(post(route).header("Origin", "https://untrusted.example").header("Authorization", "Bearer " + access)
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM account_deactivation_requests", Integer.class));
    }

    // O aceite não desativa; só a confirmação pelo token de e-mail revoga as credenciais.
    @Test void completeHttpFlowAndReplay() throws Exception {
        request(access, "{\"currentPassword\":\"Current123!\",\"userId\":999999}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expiresInSeconds").value(900)).andExpect(jsonPath("$.token").doesNotExist());
        assertTrue(users.findById(actor).orElseThrow().isActive()); verifyNoInteractions(email);
        request(access, "{\"currentPassword\":\"Current123!\"}").andExpect(status().isTooManyRequests());
        assertTrue(dispatcher.dispatchOne()); assertFalse(dispatcher.dispatchOne());
        assertTrue(delivered.getFirst().contains("http://localhost:3000/confirm-account-deactivation.html#token="));
        String token = deliveredToken(0); assertNull(jwt.validateAccessTokenAndGetUserId(token));
        confirm(token).andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertFalse(users.findById(actor).orElseThrow().isActive());
        request(access, "{\"currentPassword\":\"Current123!\"}").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("X-Sonarfy-CSRF", "1")
                .cookie(new jakarta.servlet.http.Cookie("sonarfy_refresh", session))).andExpect(status().isUnauthorized());
        confirm(token).andExpect(status().isBadRequest());
    }

    // JSON inválido, credenciais inadequadas e tokens de outro propósito não criam autorizações.
    @ParameterizedTest @ValueSource(strings = {"anonymous", "purpose", "missing", "blank", "wrong", "bytes"})
    void rejectsInvalidRequests(String scenario) throws Exception {
        String bearer = scenario.equals("anonymous") ? null : scenario.equals("purpose") ? jwt.generatePasswordResetToken(Long.toString(actor)) : access;
        String body = switch (scenario) {
            case "missing" -> "{}";
            case "blank" -> "{\"currentPassword\":\" \"}";
            case "wrong" -> "{\"currentPassword\":\"Wrong123!\"}";
            case "bytes" -> "{\"currentPassword\":\"" + "é".repeat(37) + "\"}";
            default -> "{\"currentPassword\":\"Current123!\"}";
        };
        request(bearer, body).andExpect(status().is(scenario.equals("missing") || scenario.equals("blank") ? 400 : 401));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM account_deactivation_requests", Integer.class));
    }

    // Falha SMTP mantém o aceite e reenvio rotaciona o segredo sem ampliar o vencimento.
    @Test void failedDeliveryRetriesWithoutExtendingDeadline() throws Exception {
        request(access, "{\"currentPassword\":\"Current123!\"}").andExpect(status().isAccepted());
        String expiry = db.queryForObject("SELECT expires_at::text FROM account_deactivation_requests", String.class);
        doAnswer(call -> {
            delivered.add(call.getArgument(2)); throw new IllegalStateException("simulated SMTP failure");
        }).when(email).sendEmail(anyString(), anyString(), anyString());
        assertTrue(dispatcher.dispatchOne()); assertFalse(dispatcher.dispatchOne());
        assertTrue(users.findById(actor).orElseThrow().isActive());
        db.update("UPDATE account_deactivation_mail_outbox SET available_at = statement_timestamp()");
        doAnswer(call -> { delivered.add(call.getArgument(2)); return "sent"; }).when(email).sendEmail(anyString(), anyString(), anyString());
        assertTrue(dispatcher.dispatchOne()); assertNotEquals(deliveredToken(0), deliveredToken(1));
        assertEquals(expiry, db.queryForObject("SELECT expires_at::text FROM account_deactivation_requests", String.class));
        confirm(deliveredToken(0)).andExpect(status().isBadRequest()); confirm(deliveredToken(1)).andExpect(status().isNoContent());
    }

    // Confirmação pública exige JSON válido e não usa Bearer nem cookie para autorizar a conta.
    @ParameterizedTest @ValueSource(strings = {"missing", "blank", "oversized", "invalid"})
    void rejectsInvalidConfirmationPayload(String scenario) throws Exception {
        String body = scenario.equals("missing") ? "{}" : "{\"token\":\""
                + (scenario.equals("blank") ? "" : scenario.equals("oversized") ? "x".repeat(257) : access) + "\"}";
        mvc.perform(post("/api/auth/account-deactivation-confirmation").header("Authorization", "Bearer invalid")
                .contentType("application/json").content(body)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertTrue(users.findById(actor).orElseThrow().isActive());
    }

    // A reserva é revalidada com o relógio do banco antes do SMTP, inclusive após invalidação.
    @ParameterizedTest @ValueSource(strings = {"expiry", "lease", "invalidate", "email"})
    void revalidatesReservationBeforeSending(String change) throws Exception {
        request(access, "{\"currentPassword\":\"Current123!\"}").andExpect(status().isAccepted());
        var reserved = mails.claim().orElseThrow(); assertTrue(mails.canSend(reserved));
        switch (change) {
            case "expiry" -> db.update("UPDATE account_deactivation_requests SET issued_at = statement_timestamp() - interval '16 minutes', expires_at = statement_timestamp()");
            case "lease" -> db.update("UPDATE account_deactivation_mail_outbox SET lease_until = statement_timestamp()");
            case "invalidate" -> new TransactionTemplate(manager).executeWithoutResult(status -> requests.invalidate(actor));
            case "email" -> db.update("UPDATE users SET email = 'different@example.com' WHERE user_id = ?", actor);
        }
        assertFalse(mails.canSend(reserved)); verifyNoInteractions(email);
    }
}

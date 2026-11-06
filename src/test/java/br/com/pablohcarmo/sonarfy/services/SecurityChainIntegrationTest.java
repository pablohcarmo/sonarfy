package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.config.CorsConfig;
import br.com.pablohcarmo.sonarfy.controllers.AuthController;
import br.com.pablohcarmo.sonarfy.controllers.UserController;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.Instant;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Cadeia de seguranca, controllers, servicos e JPA reais; apenas e-mail simulado.
@SpringJUnitWebConfig(SecurityChainIntegrationTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=security-chain-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com",
        "sonarfy.test.jdbc-url=jdbc:h2:mem:security_chain;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class SecurityChainIntegrationTest {
    @Configuration
    @EnableWebMvc
    @Import({EmailReservationIntegrationTest.Config.class, SecurityConfig.class, CorsConfig.class,
            AuthController.class, UserController.class, AuthService.class})
    static class Config {}

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired DataSource dataSource;
    @Autowired EmailService transport;
    @Autowired EmailTemplateService templates;
    @Value("${api.security.token.secret}") String signingKey;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        reset(transport, templates);
        when(templates.activation(any(User.class), anyString())).thenAnswer(call ->
                new EmailContent("activation", call.getArgument(1), java.util.Map.of()));
        when(templates.welcome(any(User.class), anyString()))
                .thenReturn(new EmailContent("welcome", "welcome", java.util.Map.of()));
        // O schema local define a data no banco; reproduz esse default no H2.
        new JdbcTemplate(dataSource).execute("ALTER TABLE users ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = new User();
        user.setHandle("security-test");
        user.setName("Security");
        user.setSurname("Test");
        user.setDisplayName("Security Test");
        user.setEmail("user@example.com");
        user.setActive(true);
        user.setVerified(true);
        user.setPermission(permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission role = new Permission();
            role.setName("ROLE_USER");
            return permissions.save(role);
        }));
        user.setPassword(encoder.encode("Correct123!"));
        user = users.saveAndFlush(user);
    }

    @Test
    void loginThenBearerEstablishesCorrectPrincipalWithoutSessionOrLeakingToNextRequest() throws Exception {
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"user@example.com\",\"password\":\"Correct123!\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = new ObjectMapper().readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
        var authenticated = mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(user.getEmail())).andReturn();
        assertNull(authenticated.getRequest().getSession(false));
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "tampered", "wrong-issuer", "no-exp", "no-sub", "bad-sub", "zero", "overflow",
            "email_confirmation", "pending_registration", "password_reset", "password_change"})
    void rejectsInvalidAccessCredentials(String scenario) throws Exception {
        var builder = JWT.create().withIssuer(scenario.equals("wrong-issuer") ? "other" : "sonarfy-api")
                .withClaim("purpose", scenario.contains("_") ? scenario : "access");
        if (!scenario.equals("no-sub")) {
            builder.withSubject(switch (scenario) {
                case "bad-sub" -> "abc";
                case "zero" -> "0";
                case "overflow" -> "9223372036854775808";
                default -> user.getId().toString();
            });
        }
        if (!scenario.equals("no-exp")) builder.withExpiresAt(Instant.now().plusSeconds(scenario.equals("expired") ? -60 : 900));
        String token = builder.sign(Algorithm.HMAC256(scenario.equals("tampered") ? "wrong-key" : signingKey));
        if (!scenario.equals("tampered")) {
            // Confere a assinatura isoladamente, inclusive no cenario de expiracao.
            assertDoesNotThrow(() -> Algorithm.HMAC256(signingKey).verify(JWT.decode(token)));
        }
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "inactive", "unverified"})
    void refusesAccountsNoLongerEligibleEvenWithValidToken(String scenario) throws Exception {
        user.setActive(!scenario.equals("inactive"));
        user.setVerified(!scenario.equals("unverified"));
        String token = jwt.generateAccessToken(user.getId().toString());
        if (scenario.equals("deleted")) users.deleteById(user.getId());
        else users.saveAndFlush(user);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationBearerStillReachesPendingEmailController() throws Exception {
        user.setVerified(false);
        users.saveAndFlush(user);
        mvc.perform(patch("/api/auth/update-pending-email").servletPath("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer " + jwt.generateRegistrationToken(user.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newEmail\":\"new@example.com\"}"))
                .andExpect(status().isOk());
        assertEquals("new@example.com", users.findById(user.getId()).orElseThrow().getEmail());
    }

    @Test
    void acceptsAccessTokenSignedWithConfiguredTestKey() throws Exception {
        String token = JWT.create().withIssuer("sonarfy-api").withSubject(user.getId().toString())
                .withClaim("purpose", "access").withExpiresAt(Instant.now().plusSeconds(900))
                .sign(Algorithm.HMAC256(signingKey));
        assertEquals(user.getId().toString(), jwt.validateAccessTokenAndGetUserId(token));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(user.getEmail()));
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/auth/register", "POST,/api/auth/login", "POST,/api/auth/resend-activation-email",
            "POST,/api/auth/forgot-password", "POST,/api/auth/reset-password", "GET,/api/auth/verify",
            "PATCH,/api/auth/update-pending-email"})
    void publicEndpointsReachControllerWithoutLogin(String method, String path) throws Exception {
        // Entrada incompleta deve chegar ao MVC e resultar em 400, sem bloqueio de autenticacao.
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(handler().handlerType(AuthController.class)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/auth/register", "GET,/api/auth/login", "GET,/api/auth/resend-activation-email",
            "GET,/api/auth/forgot-password", "GET,/api/auth/reset-password", "POST,/api/auth/verify",
            "POST,/api/auth/update-pending-email", "GET,/api/auth/unknown", "POST,/api/auth/logout",
            "GET,/error", "GET,/error/unknown", "OPTIONS,/api/users/me"})
    void otherMethodsAndPathsRequireAuthentication(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isUnauthorized());
    }

    @Test
    void internalErrorDispatchIsNotBlockedByAuthentication() throws Exception {
        // O contexto de teste nao possui BasicErrorController, por isso o destino retorna 404.
        mvc.perform(get("/error").with(request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            return request;
        })).andExpect(status().isNotFound());
    }

    @Test
    void preflightAllowsAuthorizationHeaderWithoutBearer() throws Exception {
        mvc.perform(options("/api/users/me").header("Origin", "http://localhost:63342")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:63342"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void corsDoesNotRemoveAuthenticationRequirementForActualRequest() throws Exception {
        mvc.perform(get("/api/users/me").header("Origin", "http://localhost:63342"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:63342"));
        mvc.perform(get("/api/users/me").header("Origin", "http://localhost:63342")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken(user.getId().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:63342"));
    }

    @Test
    void preflightRejectsMethodOutsideCorsPolicy() throws Exception {
        mvc.perform(options("/api/users/me").header("Origin", "http://localhost:63342")
                        .header("Access-Control-Request-Method", "TRACE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationConfirmationLoginAndProtectedProfileWorkThroughSecurityChain() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"New","surname":"Account","handle":"new-account",
                 "email":"new@example.com","password":"TestPassword123!",
                 "city":"Brasilia","country":"Brasil","birthDate":"2000-01-01"}
                """))
                .andExpect(status().isCreated());
        String credentials = "{\"login\":\"new@example.com\",\"password\":\"TestPassword123!\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isUnauthorized());
        var email = org.mockito.ArgumentCaptor.forClass(EmailContent.class);
        verify(transport).sendHtmlEmail(eq("new@example.com"), anyString(), email.capture());
        String confirmationToken = email.getValue().textBody().split("\\?token=")[1].split("\\s")[0];
        mvc.perform(get("/api/auth/verify").param("token", confirmationToken)).andExpect(status().isOk());
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn();
        String accessToken = new ObjectMapper().readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@example.com"));
    }
}

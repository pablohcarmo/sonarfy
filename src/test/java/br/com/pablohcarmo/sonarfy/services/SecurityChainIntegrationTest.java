package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
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
    @Import({EmailReservationIntegrationTest.Config.class, SecurityConfig.class,
            AuthController.class, UserController.class, AuthService.class})
    static class Config {}

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired DataSource dataSource;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        // O schema local define a data no banco; reproduz esse default no H2.
        new JdbcTemplate(dataSource).execute("ALTER TABLE users ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = new User();
        user.setHandle("security-test");
        user.setName("Security");
        user.setSurname("Test");
        user.setEmail("user@example.com");
        user.setActive(true);
        user.setVerified(true);
        user.setPermissionId(permissions.findByName("ROLE_USER").orElseGet(() -> {
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
        String token = builder.sign(Algorithm.HMAC256(scenario.equals("tampered") ? "wrong-key" : "security-chain-test-secret"));
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
}

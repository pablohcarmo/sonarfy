package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.*;
import br.com.pablohcarmo.sonarfy.controllers.*;
import br.com.pablohcarmo.sonarfy.entities.*;
import br.com.pablohcarmo.sonarfy.repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// HTTP, segurança, serviço e persistência reais; PostgreSQL somente em banco descartável.
@SpringJUnitWebConfig(PersonalDataIntegrationTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=personal-data-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class PersonalDataIntegrationTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, AuthController.class, UserController.class, AuthService.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto",
                    environment.getProperty("sonarfy.test.schema-mode", "create-drop")));
            return factory;
        }
        @Autowired Environment environment;
    }

    @Autowired WebApplicationContext context;
    @MockitoSpyBean UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired RefreshTokenRepository sessions;
    @Autowired JwtService jwt;
    @Autowired DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void prepare() {
        sessions.deleteAll();
        users.deleteAll();
        new JdbcTemplate(dataSource).execute("ALTER TABLE users ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = account("original");
    }

    // A resposta preserva o contrato JSON mesmo após a conversão para record.
    @ParameterizedTest
    @ValueSource(strings = {"original", "ORIGINAL", "new.handle"})
    void updatesPersonalDataAndPreservesPrivateAndProfileFields(String handle) throws Exception {
        User other = account("other");
        var input = payload();
        input.put("handle", handle);
        input.put("id", other.getId());
        input.put("birthDate", LocalDate.now().minusYears(13).toString());
        input.put("email", "ignored@example.com");
        input.put("password", "ignored");
        input.put("permission", Map.of("id", 1));
        input.put("active", false);
        input.put("verified", false);
        input.put("avatar", "ignored");
        input.put("wallpaper", "ignored");
        input.put("biography", "ignored");
        input.put("displayName", "ignored");
        update(user, input).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(handle))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.isActive").doesNotExist())
                .andExpect(jsonPath("$.profilePicture").value("avatar"))
                .andExpect(jsonPath("$.displayName").doesNotExist());
        User saved = users.findById(user.getId()).orElseThrow();
        assertEquals("Chosen profile name", saved.getDisplayName());
        assertEquals("Maria", saved.getName());
        assertEquals("Silva", saved.getSurname());
        assertEquals("Campinas", saved.getCity());
        assertEquals("Brasil", saved.getCountry());
        assertEquals(LocalDate.now().minusYears(13), saved.getBirthDate());
        assertEquals(user.getEmail(), saved.getEmail());
        assertEquals(user.getPassword(), saved.getPassword());
        assertEquals(user.getPermission().getId(), saved.getPermission().getId());
        assertTrue(saved.isVerified());
        assertEquals("wallpaper", saved.getWallpaper());
        assertEquals("bio", saved.getBiography());
        assertEquals("Original", users.findById(other.getId()).orElseThrow().getName());
        mvc.perform(get("/api/users/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(handle));
    }

    // Campos omitidos, nulos, vazios, fora do formato ou do limite não persistem parcialmente.
    @ParameterizedTest
    @CsvSource({"name,51", "surname,51", "handle,16", "city,101", "country,101", "birthDate,0"})
    void rejectsInvalidFieldsAtomically(String field, int overflow) throws Exception {
        for (String invalid : Arrays.asList(null, "", " ", "123!", "A".repeat(overflow))) {
            var input = payload();
            input.put(field, invalid);
            update(user, input).andExpect(status().isBadRequest());
        }
        var omitted = payload();
        omitted.remove(field);
        update(user, omitted).andExpect(status().isBadRequest());
        assertEquals("Original", users.findById(user.getId()).orElseThrow().getName());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 12, -1})
    void rejectsUnderageAndFutureDates(int years) throws Exception {
        var input = payload();
        input.put("birthDate", years == 12 ? LocalDate.now().minusYears(13).plusDays(1).toString()
                : LocalDate.now().minusYears(years).toString());
        update(user, input).andExpect(status().isBadRequest());
        assertEquals("Original", users.findById(user.getId()).orElseThrow().getName());
    }

    @Test
    void acceptsSchemaLimitsWithoutChangingDisplayName() throws Exception {
        var input = payload();
        input.put("name", "A".repeat(50)); input.put("surname", "B".repeat(50));
        input.put("handle", "h".repeat(15));
        input.put("city", "C".repeat(100)); input.put("country", "D".repeat(100));
        update(user, input).andExpect(status().isOk());
        User saved = users.findById(user.getId()).orElseThrow();
        assertEquals("Chosen profile name", saved.getDisplayName());
        assertEquals(50, saved.getSurname().length());
    }

    @Test
    void occupiedHandleIsCaseInsensitiveAndRollsBack() throws Exception {
        account("occupied");
        var input = payload(); input.put("handle", "OCCUPIED");
        update(user, input).andExpect(status().isConflict());
        assertEquals("Original", users.findById(user.getId()).orElseThrow().getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "pending_registration", "email_confirmation", "password_change", "password_reset", "inactive", "unverified", "expired"})
    void rejectsUnauthorizedUpdates(String scenario) throws Exception {
        String token = switch (scenario) {
            case "missing" -> "";
            case "invalid" -> "Bearer invalid";
            case "pending_registration" -> "Bearer " + jwt.generateRegistrationToken(user.getId().toString());
            case "email_confirmation" -> "Bearer " + jwt.generateEmailConfirmationToken(user.getId().toString());
            case "password_change" -> "Bearer " + jwt.generatePasswordChangeToken(user.getId().toString());
            case "password_reset" -> "Bearer " + jwt.generatePasswordResetToken(user.getId().toString());
            case "expired" -> "Bearer " + com.auth0.jwt.JWT.create().withIssuer("sonarfy-api")
                    .withSubject(user.getId().toString()).withClaim("purpose", "access")
                    .withExpiresAt(java.time.Instant.now().minusSeconds(1))
                    .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("personal-data-test-secret"));
            default -> bearer(user);
        };
        if (scenario.equals("inactive")) user.setActive(false);
        if (scenario.equals("unverified")) user.setVerified(false);
        users.saveAndFlush(user);
        mvc.perform(put("/api/users/me").header("Authorization", token)
                .contentType("application/json").content(json.writeValueAsString(payload())))
                .andExpect(status().isUnauthorized());
        assertEquals("Original", users.findById(user.getId()).orElseThrow().getName());
    }

    // A barreira força ambas as consultas prévias a não encontrar o handle antes dos updates.
    @Test
    @EnabledIfSystemProperty(named = "sonarfy.test.postgres", matches = "true")
    void concurrentCaseVariantsReturnOneSuccessAndOneConflict() throws Exception {
        User other = account("other");
        var ready = new CountDownLatch(2);
        doAnswer(call -> {
            assertEquals(0, new JdbcTemplate(dataSource).queryForObject(
                    "SELECT count(*) FROM users WHERE lower(handle) = lower(?)", Integer.class, call.getArgument(0, String.class)));
            ready.countDown();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            return Optional.empty();
        }).when(users).findByHandleIgnoreCase(argThat(h -> h.equalsIgnoreCase("race")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> race(user, "Race"));
            var second = pool.submit(() -> race(other, "RACE"));
            assertEquals(Set.of(200, 409), Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
        }
        List<User> saved = users.findAll();
        assertEquals(1, saved.stream().filter(u -> u.getHandle().equalsIgnoreCase("race")).count());
        assertEquals(1, saved.stream().filter(u -> u.getName().equals("Original")).count());
    }

    private int race(User actor, String handle) throws Exception {
        var input = payload(); input.put("handle", handle);
        return update(actor, input).andReturn().getResponse().getStatus();
    }

    private ResultActions update(User actor, Map<String, Object> input) throws Exception {
        return mvc.perform(put("/api/users/me").header("Authorization", bearer(actor))
                .contentType("application/json").content(json.writeValueAsString(input)));
    }

    private String bearer(User actor) { return "Bearer " + jwt.generateAccessToken(actor.getId().toString()); }

    private Map<String, Object> payload() {
        return new HashMap<>(Map.of("name", "Maria", "surname", "Silva", "handle", "maria",
                "city", "Campinas", "country", "Brasil", "birthDate", "2000-01-01"));
    }

    private User account(String handle) {
        var role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission value = new Permission(); value.setName("ROLE_USER"); return permissions.save(value);
        });
        User value = new User(null, role, "Original", "Account", "Chosen profile name", handle,
                handle + "@example.com", "test-hash", true, true, "Brasilia", "Brasil", "avatar", "wallpaper", "bio",
                LocalDate.of(2000, 1, 1), null, null, null, null);
        return users.saveAndFlush(value);
    }
}

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
    @ValueSource(strings = {"original", "ORIGINAL", "new.handle", "A_Z-9.x", "12345", "ABCDEFGHIJKLMNO"})
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
        String expectedHandle = handle.toLowerCase(Locale.ROOT);
        update(user, input).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(expectedHandle))
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(expectedHandle));
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

    // Os dois endpoints rejeitam datas ausentes, impossíveis ou fora do formato contratado.
    @ParameterizedTest
    @ValueSource(strings = {"omitted", "null", "", " ", "2000-02-30", "2001-02-29", "2002-02-29", "2000-13-01",
            "2000-01-32", "31/12/2000", "2000-1-1", "invalid", "2000-01-01T00:00:00",
            "2000-01-01T00:00:00Z", "array", "number"})
    void rejectsInvalidBirthDatesInRegistrationAndUpdate(String value) throws Exception {
        var input = payload();
        if (value.equals("omitted")) input.remove("birthDate");
        else input.put("birthDate", switch (value) {
            case "null" -> null;
            case "array" -> List.of(2000, 1, 1);
            case "number" -> 946684800000L;
            default -> value;
        });
        assertBirthDateRejectedAtomically(input);
    }

    // A idade mínima vale nos dois fluxos, incluindo o aniversário de hoje e de amanhã.
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2, 3})
    void verifiesAgeBoundaryInRegistrationAndUpdate(int scenario) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate birth = switch (scenario) {
            case -1 -> today.minusYears(13).minusDays(1);
            case 0 -> today.minusYears(13);
            case 1 -> today.minusYears(13).plusDays(1);
            case 2 -> today;
            default -> today.plusDays(1);
        };
        var input = payload();
        input.put("birthDate", birth.toString());
        if (scenario > 0) {
            assertBirthDateRejectedAtomically(input);
        } else {
            update(user, input).andExpect(status().isOk())
                    .andExpect(jsonPath("$.birthDate").value(birth.toString()));
            assertEquals(birth, users.findById(user.getId()).orElseThrow().getBirthDate());
            input.put("email", "birth-test@example.com");
            input.put("handle", "birth-test");
            input.put("password", "BirthTest123!");
            mvc.perform(post("/api/auth/register").contentType("application/json")
                            .content(json.writeValueAsString(input))).andExpect(status().isCreated());
            assertEquals(birth, users.findByEmailIgnoreCase("birth-test@example.com").orElseThrow().getBirthDate());
        }
    }

    // Datas bissextas válidas mantêm o dia na persistência e na resposta HTTP.
    @Test
    void preservesValidLeapDateInBothFlows() throws Exception {
        var input = payload();
        input.put("birthDate", "2000-02-29");
        update(user, input).andExpect(status().isOk()).andExpect(jsonPath("$.birthDate").value("2000-02-29"));
        input.put("email", "leap@example.com");
        input.put("handle", "leap");
        input.put("password", "BirthTest123!");
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(json.writeValueAsString(input))).andExpect(status().isCreated());
        assertEquals(LocalDate.of(2000, 2, 29), users.findByEmailIgnoreCase("leap@example.com")
                .orElseThrow().getBirthDate());
    }

    // Recusa não altera dados, auditoria ou sessões; cadastro inválido não cria conta.
    private void assertBirthDateRejectedAtomically(Map<String, Object> input) throws Exception {
        User before = users.findById(user.getId()).orElseThrow();
        String refresh = context.getBean(RefreshTokenService.class).issue(user);
        update(user, input).andExpect(status().isBadRequest());
        input.put("email", "invalid-birth@example.com");
        input.put("password", "BirthTest123!");
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
        assertEquals(1, users.count());
        User after = users.findById(user.getId()).orElseThrow();
        assertEquals(before.getBirthDate(), after.getBirthDate());
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getHandle(), after.getHandle());
        assertEquals(before.getCity(), after.getCity());
        assertEquals(before.getCountry(), after.getCountry());
        assertEquals(before.getLastUpdateDate(), after.getLastUpdateDate());
        assertNotNull(context.getBean(RefreshTokenService.class).rotate(refresh));
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

    // Cadastro e edição rejeitam os mesmos caracteres e limites sem modificar a conta.
    @ParameterizedTest
    @ValueSource(strings = {"@valid", " valid", "valid ", "two words", "caf\u00e9", "a/b", "a!b", "a+b", "AAAAAAAAAAAAAAAA"})
    void registrationAndUpdateRejectInvalidHandles(String handle) throws Exception {
        User before = users.findById(user.getId()).orElseThrow();
        var input = payload();
        input.put("handle", handle);
        update(user, input).andExpect(status().isBadRequest());
        input.put("email", "new@example.com");
        input.put("password", "Registration123!");
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
        assertEquals(1, users.count());
        User after = users.findById(user.getId()).orElseThrow();
        assertEquals(before.getHandle(), after.getHandle());
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getLastUpdateDate(), after.getLastUpdateDate());
    }

    // O cadastro persiste a forma canônica e continua recusando duplicatas em outra caixa.
    @ParameterizedTest
    @ValueSource(strings = {"New.Handle", "A_Z-9.x", "12345", "ABCDEFGHIJKLMNO"})
    void registrationNormalizesHandleAndRejectsCaseInsensitiveDuplicate(String handle) throws Exception {
        var input = payload();
        input.put("handle", handle);
        input.put("email", "new@example.com");
        input.put("password", "Registration123!");
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(json.writeValueAsString(input))).andExpect(status().isCreated());
        assertEquals(handle.toLowerCase(Locale.ROOT), users.findByEmailIgnoreCase("new@example.com")
                .orElseThrow().getHandle());
        input.put("email", "duplicate@example.com");
        input.put("handle", handle.toUpperCase(Locale.ROOT));
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(json.writeValueAsString(input))).andExpect(status().isConflict());
        assertEquals(2, users.count());
    }

    // A edição preserva sessões e ID; somente o handle atualizado autentica a conta.
    @Test
    void loginUsesUpdatedHandleWhileExistingAccessAndRefreshRemainValid() throws Exception {
        user.setPassword(context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
                .encode("HandleTest123!"));
        users.saveAndFlush(user);
        String access = bearer(user);
        String refresh = context.getBean(RefreshTokenService.class).issue(user);
        var input = payload();
        input.put("handle", "New.Name-9");
        update(user, input).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value("new.name-9"));
        for (String login : List.of("new.name-9", "NEW.NAME-9", "original")) {
            mvc.perform(post("/api/auth/login").header("X-Sonarfy-CSRF", "1").contentType("application/json")
                            .content(json.writeValueAsString(Map.of("login", login, "password", "HandleTest123!"))))
                    .andExpect(login.equals("original") ? status().isUnauthorized() : status().isOk());
        }
        mvc.perform(get("/api/users/me").header("Authorization", access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.handle").value("new.name-9"));
        mvc.perform(post("/api/auth/refresh").header("X-Sonarfy-CSRF", "1")
                        .cookie(new jakarta.servlet.http.Cookie("sonarfy_refresh", refresh)))
                .andExpect(status().isOk());
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

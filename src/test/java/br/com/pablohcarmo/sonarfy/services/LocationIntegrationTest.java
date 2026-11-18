package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.GeoNamesClient;
import br.com.pablohcarmo.sonarfy.config.*;
import br.com.pablohcarmo.sonarfy.controllers.*;
import br.com.pablohcarmo.sonarfy.entities.*;
import br.com.pablohcarmo.sonarfy.repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Cadeia HTTP e cliente reais contra a simulação Postman; H2 isolado e e-mail simulado.
@EnabledIfSystemProperty(named = "sonarfy.test.location", matches = "true")
@SpringJUnitWebConfig(LocationIntegrationTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=location-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com",
        "sonarfy.test.jdbc-url=jdbc:h2:mem:locations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class LocationIntegrationTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, AuthController.class, UserController.class,
            AuthService.class, LocationController.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override GeoNamesClient locationClient() {
            return new GeoNamesClient(System.getProperty("sonarfy.test.location-url",
                    "http://127.0.0.1:55441/geonames"), "test-only");
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired RefreshTokenRepository sessions;
    @Autowired RefreshTokenService refresh;
    @Autowired JwtService jwt;
    @Autowired DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void prepare() {
        sessions.deleteAll(); users.deleteAll();
        new JdbcTemplate(dataSource).execute("ALTER TABLE users ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
        Permission role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            Permission value = new Permission(); value.setName("ROLE_USER"); return permissions.save(value);
        });
        user = users.saveAndFlush(new User(null, role, "Original", "User", "Display", "original",
                "original@example.com", "hash", true, true, "Brasilia", "Brasil", null, null, null,
                LocalDate.of(2000, 1, 1), null, null, null, null));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // Cidades homônimas preservam identificadores e países distintos na busca pública.
    @Test
    void searchesPubliclyAndDisambiguatesHomonyms() throws Exception {
        mvc.perform(get("/api/locations/cities").param("q", "Santa"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cityId").value(2)).andExpect(jsonPath("$[0].country").value("Brasil"))
                .andExpect(jsonPath("$[1].cityId").value(3)).andExpect(jsonPath("$[1].country").value("Estados Unidos"));
        mvc.perform(get("/api/locations/cities").param("q", "missing")).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get("/api/locations/cities")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/locations/cities").param("q", "x".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/locations/cities")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/me").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // Falhas do provedor não viram lista vazia nem expõem o conteúdo remoto.
    @ParameterizedTest @ValueSource(strings = {"quota", "malformed", "a", ""})
    void rejectsInvalidSearchAndProviderErrors(String query) throws Exception {
        mvc.perform(get("/api/locations/cities").param("q", query))
                .andExpect(query.length() < 2 ? status().isBadRequest() : status().isServiceUnavailable());
    }

    // Cadastro exige o identificador e ignora nomes ou países fornecidos pelo cliente.
    @Test
    void registrationDerivesLocationFromIdentifier() throws Exception {
        var input = payload(); input.put("cityId", 1); input.put("city", "Fake"); input.put("country", "Fake");
        input.put("email", "new@example.com"); input.put("password", "LocationTest123!");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isCreated());
        User saved = users.findByEmailIgnoreCase("new@example.com").orElseThrow();
        assertEquals("Campinas", saved.getCity()); assertEquals("Brasil", saved.getCountry());
    }

    // Texto legado não substitui a seleção obrigatória no cadastro.
    @Test
    void registrationRequiresIdentifierEvenWithCityAndCountryText() throws Exception {
        var input = payload(); input.put("city", "Campinas"); input.put("country", "Brasil");
        input.put("email", "new@example.com"); input.put("password", "LocationTest123!");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
        assertEquals(1, users.count());
    }

    // Nenhum fluxo converte texto ou trunca decimais para selecionar outra cidade.
    @Test
    void rejectsCoercedAndOverflowingIdentifiers() throws Exception {
        for (Object id : List.of("1", 1.5, true, new java.math.BigInteger("9223372036854775808"))) {
            var input = payload(); input.put("cityId", id);
            update(input).andExpect(status().isBadRequest());
            input.put("email", "invalid-id@example.com"); input.put("password", "LocationTest123!");
            mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(input)))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(1, users.count());
    }

    // Ausência de cityId preserva inclusive localização antiga e não exige serviço configurado.
    @Test
    void omittedIdentifierPreservesLocationAndSessions() throws Exception {
        var provider = context.getBean(GeoNamesClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "username", "");
        try {
            String token = refresh.issue(user);
            var input = payload(); input.put("city", "Fake"); input.put("country", "Fake");
            update(input).andExpect(status().isOk()).andExpect(jsonPath("$.city").value("Brasilia"))
                    .andExpect(jsonPath("$.country").value("Brasil"));
            assertNotNull(refresh.rotate(token));
            mvc.perform(get("/api/locations/cities").param("q", "Camp")).andExpect(status().isServiceUnavailable());
            input.put("cityId", 1);
            update(input).andExpect(status().isServiceUnavailable());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(provider, "username", "test-only");
        }
    }

    // O país da cidade selecionada prevalece, mesmo quando o texto enviado for diferente.
    @ParameterizedTest @CsvSource({"2,Brasil", "3,Estados Unidos"})
    void updatesLocationUsingTheSelectedCity(long id, String country) throws Exception {
        var input = payload(); input.put("cityId", id); input.put("country", "Fake");
        update(input).andExpect(status().isOk()).andExpect(jsonPath("$.city").value("Santa Maria"))
                .andExpect(jsonPath("$.country").value(country));
        assertEquals(country, users.findById(user.getId()).orElseThrow().getCountry());
    }

    // Erro, identificador inválido e timeout preservam cadastro, auditoria e refresh.
    @ParameterizedTest
    @CsvSource({"0,400", "-1,400", "9001,400", "9002,400", "9003,503", "9004,503", "9005,503", "9006,503"})
    void providerFailuresDoNotWritePartialData(long id, int expected) throws Exception {
        User before = users.findById(user.getId()).orElseThrow();
        String token = refresh.issue(user);
        var input = payload(); input.put("cityId", id);
        update(input).andExpect(status().is(expected));
        User after = users.findById(user.getId()).orElseThrow();
        assertEquals(before.getName(), after.getName()); assertEquals(before.getCity(), after.getCity());
        assertEquals(before.getCountry(), after.getCountry()); assertEquals(before.getLastUpdateDate(), after.getLastUpdateDate());
        input.put("email", "failed@example.com"); input.put("password", "LocationTest123!");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().is(expected));
        assertEquals(1, users.count()); assertNotNull(refresh.rotate(token));
    }

    private org.springframework.test.web.servlet.ResultActions update(Map<String, Object> input) throws Exception {
        return mvc.perform(put("/api/users/me").header("Authorization", "Bearer " + jwt.generateAccessToken(user.getId().toString()))
                .contentType("application/json").content(json.writeValueAsString(input)));
    }

    private Map<String, Object> payload() {
        return new HashMap<>(Map.of("name", "Maria", "surname", "Silva", "handle", "maria", "birthDate", "2000-01-01"));
    }
}

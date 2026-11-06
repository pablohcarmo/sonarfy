package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.controllers.AuthController;
import br.com.pablohcarmo.sonarfy.entities.AccessLog;
import br.com.pablohcarmo.sonarfy.repositories.AccessLogRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in check against imported tables. Never creates/drops schema; test data rolls back. */
@EnabledIfSystemProperty(named = "sonarfy.test.existing-schema", matches = "true")
@SpringJUnitConfig(ExistingSchemaIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "app.frontend-url=http://localhost:3000",
        "api.security.token.secret=isolated-test-secret",
        "spring.mail.username=noreply@example.com"
})
@Transactional
class ExistingSchemaIntegrationTest {
    @Configuration
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean
        @Override
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            return factory;
        }
    }

    @Autowired UserService service;
    @Autowired UserRepository users;
    @Autowired AccessLogRepository logs;
    @Autowired JwtService jwt;
    @Autowired AuthEmailService emails;
    @Autowired PasswordEncoder encoder;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationLoginAndAccessLogWorkWithImportedSchema(boolean longName) throws Exception {
        String handle = "check" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String email = handle + "@example.com";
        String name = longName ? "A".repeat(50) : "Schema";
        String surname = longName ? "B".repeat(50) : "Check";
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                service, jwt, emails, new AuthService(users, encoder, jwt))).build();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"%s","surname":"%s","handle":"%s","email":"%s",
                 "password":"TestPassword123!","city":"Brasilia","country":"Brasil",
                 "birthDate":"2000-01-01"}
                """.formatted(name, surname, handle, email))).andExpect(status().isCreated());
        users.flush();
        var user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertEquals(longName ? "A".repeat(50) + " " + "B".repeat(9) : "Schema Check", user.getDisplayName());
        String login = "{\"login\":\"" + email + "\",\"password\":\"TestPassword123!\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isUnauthorized());
        user.setVerified(true);
        users.saveAndFlush(user);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());

        logs.saveAndFlush(new AccessLog(user, "127.0.0.1"));
        var saved = logs.findTopByUserOrderByDateTimeAccessDesc(user).orElseThrow();
        assertNotNull(saved.getDateTimeAccess());
        assertEquals("127.0.0.1", saved.getIpAddress());
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.controllers.AuthController;
import br.com.pablohcarmo.sonarfy.entities.AccessLog;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import br.com.pablohcarmo.sonarfy.repositories.AccessLogRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in check against imported tables. Never creates/drops schema; test data rolls back. */
@EnabledIfSystemProperty(named = "sonarfy.test.existing-schema", matches = "true")
@SpringJUnitConfig(ExistingSchemaIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "app.frontend-url=http://localhost:3000",
        "api.security.token.secret=isolated-test-secret",
        "spring.mail.username=noreply@example.com",
        "spotify_token_encryption_key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@Transactional
class ExistingSchemaIntegrationTest {
    @Configuration
    @Import({SpotifyConnectionService.class, SpotifyTokenEncryptionService.class})
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
    @Autowired PermissionRepository permissions;
    @Autowired SpotifyConnectionRepository spotifyConnections;
    @Autowired SpotifyConnectionService spotifyConnectionService;
    @Autowired SpotifyTokenEncryptionService tokenEncryption;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @Test
    void fullConstructorPersistsDisplayNameWithImportedSchema() {
        String handle = "ctor" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var permission = permissions.findByName("ROLE_USER").orElseThrow();
        User user = new User(null, permission, "Constructor", "Account", "Nome escolhido",
                handle, handle + "@example.com", encoder.encode("TestPassword123!"), true, true,
                "Brasilia", "Brasil", null, null, null, LocalDate.of(2000, 1, 1),
                null, null, null, null);

        users.saveAndFlush(user);
        entityManager.clear();

        assertEquals("Nome escolhido", users.findById(user.getId()).orElseThrow().getDisplayName());
    }

    @Test
    void spotifyConnectionPersistsEncryptedTokensAndReconnectUpdatesSingleRow() {
        var permission = permissions.findByName("ROLE_USER").orElseThrow();
        String handle = "sp" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = new User(null, permission, "Spotify", "Check", "Spotify Check", handle,
                handle + "@example.com", "test-hash", true, true, "Brasilia", "Brasil",
                null, null, null, LocalDate.of(2000, 1, 1), null, null, null, null);
        users.saveAndFlush(user);

        spotifyConnectionService.connect(user.getId(), 0L, new SpotifyTokenResponseDto(
                "access-one", "Bearer", 3600L, "refresh-one", "user-library-read"));
        entityManager.flush();
        entityManager.clear();
        var saved = spotifyConnections.findByUserId(user.getId()).orElseThrow();
        Long connectionId = saved.getId();
        var connectedAt = saved.getConnectedAt();
        assertNotEquals("access-one", saved.getAccessTokenEncrypted());
        assertNotEquals("refresh-one", saved.getRefreshTokenEncrypted());
        assertEquals("access-one", tokenEncryption.decrypt(saved.getAccessTokenEncrypted(), user.getId(), "access"));
        assertEquals("refresh-one", tokenEncryption.decrypt(saved.getRefreshTokenEncrypted(), user.getId(), "refresh"));
        assertEquals("user-library-read", saved.getScopes());

        spotifyConnectionService.connect(user.getId(), 0L, new SpotifyTokenResponseDto(
                "access-two", "Bearer", 3600L, "refresh-two", null));
        entityManager.flush();
        entityManager.clear();
        var reconnected = spotifyConnections.findByUserId(user.getId()).orElseThrow();
        assertEquals(connectionId, reconnected.getId());
        assertEquals(connectedAt, reconnected.getConnectedAt());
        assertNull(reconnected.getScopes());
        assertEquals(3600L, java.time.Duration.between(
                reconnected.getUpdatedAt(), reconnected.getAccessTokenExpiresAt()).getSeconds());
        assertEquals("access-two", tokenEncryption.decrypt(reconnected.getAccessTokenEncrypted(), user.getId(), "access"));
        assertEquals("refresh-two", tokenEncryption.decrypt(reconnected.getRefreshTokenEncrypted(), user.getId(), "refresh"));
        assertEquals(1, spotifyConnections.countByUserId(user.getId()));
    }

    @Test
    void concurrentSpotifyConnectionsForOneUserAreSerialized() throws Exception {
        var permission = permissions.findByName("ROLE_USER").orElseThrow();
        String handle = "sc" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = new User(null, permission, "Spotify", "Race", "Spotify Race", handle,
                handle + "@example.com", "test-hash", true, true, "Brasilia", "Brasil",
                null, null, null, LocalDate.of(2000, 1, 1), null, null, null, null);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long userId = requiresNew.execute(status -> users.saveAndFlush(user).getId());

        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> connectConcurrently(requiresNew, userId, "access-a", ready, start));
            var second = executor.submit(() -> connectConcurrently(requiresNew, userId, "access-b", ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
            assertEquals(1L, requiresNew.execute(status -> spotifyConnections.countByUserId(userId)).longValue());
        } finally {
            requiresNew.executeWithoutResult(status -> users.deleteById(userId));
        }
    }

    private void connectConcurrently(TransactionTemplate transaction, Long userId, String accessToken,
                                     CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            transaction.executeWithoutResult(status -> spotifyConnectionService.connect(userId, 0L,
                    new SpotifyTokenResponseDto(accessToken, "Bearer", 3600L,
                            "refresh-" + accessToken, "user-library-read")));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent connection test interrupted.");
        }
    }

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

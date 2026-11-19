package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.ProfileController;
import br.com.pablohcarmo.sonarfy.controllers.UserController;
import br.com.pablohcarmo.sonarfy.entities.*;
import br.com.pablohcarmo.sonarfy.repositories.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import java.time.LocalDate;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real security, service and JPA; this context always creates tables in its own disposable H2 database.
@SpringJUnitWebConfig(ProfileIntegrationTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=profile-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class ProfileIntegrationTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ProfileController.class, ProfileService.class, UserController.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override DataSource dataSource(org.springframework.core.env.Environment env) {
            return new DriverManagerDataSource("jdbc:h2:mem:profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired JwtService jwt;
    MockMvc mvc;
    User actor;

    @BeforeEach
    void prepare() {
        users.deleteAll();
        new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class))
                .execute("ALTER TABLE users ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        actor = account("owner");
    }

    // Both routes expose exactly the allowed fields and derive ownership from the principal ID.
    @ParameterizedTest @ValueSource(strings = {"/me", "/by-handle/OWNER"})
    void readsOwnProfileWithoutPrivateFields(String path) throws Exception {
        mvc.perform(get("/api/profile" + path).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$.identity.length()").value(8))
                .andExpect(jsonPath("$.identity.id").value(actor.getId()))
                .andExpect(jsonPath("$.identity.handle").value("owner"))
                .andExpect(jsonPath("$.identity.displayName").value("D".repeat(60)))
                .andExpect(jsonPath("$.identity.avatarUrl").value("https://example.com/avatar"))
                .andExpect(jsonPath("$.identity.coverUrl").value("https://example.com/cover"))
                .andExpect(jsonPath("$.identity.biography").value("Profile bio"))
                .andExpect(jsonPath("$.identity.location.city").value("Brasilia"))
                .andExpect(jsonPath("$.identity.location.country").value("Brasil"))
                .andExpect(jsonPath("$.identity.joinedAt").value(org.hamcrest.Matchers.matchesPattern(".*(Z|[+-][0-9]{2}:[0-9]{2})$")))
                .andExpect(jsonPath("$.viewer.isSelf").value(true)).andExpect(jsonPath("$.viewer.canEdit").value(true));
    }

    // A third party receives no account fields, and nullable media does not fabricate defaults.
    @Test
    void readsOtherProfileAndPreservesAccountContract() throws Exception {
        User other = account("other");
        other.setAvatar(null); other.setWallpaper(null); other.setBiography(null); users.saveAndFlush(other);
        mvc.perform(get("/api/profile/by-handle/other").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.identity.id").value(other.getId()))
                .andExpect(jsonPath("$.identity.length()").value(8))
                .andExpect(jsonPath("$.identity.avatarUrl").isEmpty()).andExpect(jsonPath("$.identity.biography").isEmpty())
                .andExpect(jsonPath("$.viewer.isSelf").value(false)).andExpect(jsonPath("$.viewer.canEdit").value(false))
                .andExpect(jsonPath("$.statistics").doesNotExist());
        mvc.perform(get("/api/users/me").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.id").value(actor.getId())).andExpect(jsonPath("$.displayName").doesNotExist());
    }

    // Missing, inactive and unverified targets have the same HTTP result.
    @ParameterizedTest @ValueSource(strings = {"missing", "inactive", "unverified"})
    void hidesUnavailableProfiles(String handle) throws Exception {
        if (!handle.equals("missing")) {
            User other = account(handle);
            other.setActive(!handle.equals("inactive")); other.setVerified(!handle.equals("unverified")); users.saveAndFlush(other);
        }
        mvc.perform(get("/api/profile/by-handle/" + handle).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    // The actual filter rejects absent, malformed, wrong-purpose and stale-account credentials on both routes.
    @ParameterizedTest @ValueSource(strings = {"/me", "/by-handle/other"})
    void requiresEligibleAuthenticatedActor(String path) throws Exception {
        mvc.perform(get("/api/profile" + path)).andExpect(status().isUnauthorized());
        String expired = com.auth0.jwt.JWT.create().withIssuer("sonarfy-api").withSubject(actor.getId().toString())
                .withClaim("purpose", "access").withExpiresAt(java.time.Instant.now().minusSeconds(60))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("profile-test-secret"));
        for (String token : new String[]{"invalid", expired, jwt.generateRegistrationToken(actor.getId().toString())})
            mvc.perform(get("/api/profile" + path).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        String access = bearer();
        actor.setActive(false); users.saveAndFlush(actor);
        mvc.perform(get("/api/profile" + path).header("Authorization", access)).andExpect(status().isUnauthorized());
        actor.setActive(true); actor.setVerified(false); users.saveAndFlush(actor);
        mvc.perform(get("/api/profile" + path).header("Authorization", access)).andExpect(status().isUnauthorized());
        users.delete(actor);
        mvc.perform(get("/api/profile" + path).header("Authorization", access)).andExpect(status().isUnauthorized());
    }

    private String bearer() { return "Bearer " + jwt.generateAccessToken(actor.getId().toString()); }

    private User account(String handle) {
        Permission role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            var value = new Permission(); value.setName("ROLE_USER"); return permissions.save(value);
        });
        return users.saveAndFlush(new User(null, role, "Private", "Account", "D".repeat(60), handle,
                handle + "@example.com", "private-hash", true, true, "Brasilia", "Brasil",
                "https://example.com/avatar", "https://example.com/cover", "Profile bio",
                LocalDate.of(2000, 1, 1), null, null, null, null));
    }
}

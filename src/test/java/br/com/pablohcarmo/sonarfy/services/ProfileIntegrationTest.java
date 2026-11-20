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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real security, service and JPA; PostgreSQL opt-in is restricted to a separate disposable test database.
@SpringJUnitWebConfig(ProfileIntegrationTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=profile-test-secret",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class ProfileIntegrationTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ProfileController.class, ProfileService.class, UserController.class})
    static class Config extends EmailReservationIntegrationTest.Config {
        @Bean @Override DataSource dataSource(org.springframework.core.env.Environment env) {
            if (Boolean.getBoolean("sonarfy.test.profile-postgres"))
                return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"
                        + Integer.getInteger("sonarfy.test.profile-port", 55449) + "/sonarfy_profile_test",
                        "profile_test", "isolated-profile-test");
            return new DriverManagerDataSource("jdbc:h2:mem:profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean @Override org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto",
                    Boolean.getBoolean("sonarfy.test.profile-postgres") ? "validate" : "create-drop"));
            return factory;
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
        new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class)).execute("delete from user_following");
        users.deleteAll();
        new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class))
                .execute("ALTER TABLE users ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
        // H2 counts VARCHAR precision in UTF-16 units; PostgreSQL counts Unicode code points.
        if (!Boolean.getBoolean("sonarfy.test.profile-postgres")) {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class));
            jdbc.execute("ALTER TABLE users ALTER COLUMN display_name VARCHAR(120)");
            jdbc.execute("ALTER TABLE users ALTER COLUMN biography VARCHAR(300)");
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        actor = account("owner");
    }

    // Both routes expose exactly the allowed fields and derive ownership from the principal ID.
    @ParameterizedTest @ValueSource(strings = {"/me", "/by-handle/OWNER"})
    void readsOwnProfileWithoutPrivateFields(String path) throws Exception {
        mvc.perform(get("/api/profile" + path).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$.identity.length()").value(8))
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
                .andExpect(jsonPath("$.viewer.isFollowing").value(false))
                .andExpect(jsonPath("$.statistics.followers").value(0)).andExpect(jsonPath("$.statistics.following").value(0));
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

    // Unicode boundaries are counted as code points, not UTF-16 units or visual grapheme clusters.
    @Test
    void updatesUnicodeTextAndPreservesAccountAndMedia() throws Exception {
        User other = account("other");
        String name = "🎵".repeat(60), bio = "🎧".repeat(150);
        String input = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "displayName", " \u00a0" + name + "\u2003 ", "biography", bio, "id", other.getId(),
                "email", "ignored@example.com", "avatar", "ignored", "wallpaper", "ignored"));
        mvc.perform(put("/api/profile/me").header("Authorization", bearer()).contentType("application/json").content(input))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.identity.displayName").value(name)).andExpect(jsonPath("$.identity.biography").value(bio))
                .andExpect(jsonPath("$.identity.id").value(actor.getId())).andExpect(jsonPath("$.identity.length()").value(8))
                .andExpect(jsonPath("$.viewer.isSelf").value(true)).andExpect(jsonPath("$.email").doesNotExist());
        User saved = users.findById(actor.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(name, saved.getDisplayName());
        org.junit.jupiter.api.Assertions.assertEquals(bio, saved.getBiography());
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/avatar", saved.getAvatar());
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/cover", saved.getWallpaper());
        org.junit.jupiter.api.Assertions.assertEquals("owner@example.com", saved.getEmail());
        org.junit.jupiter.api.Assertions.assertEquals("private-hash", saved.getPassword());
        org.junit.jupiter.api.Assertions.assertEquals("Private", saved.getName());
        org.junit.jupiter.api.Assertions.assertEquals("D".repeat(60), users.findById(other.getId()).orElseThrow().getDisplayName());
    }

    // A missing field preserves its current value; only explicit null or empty text clears the bio.
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"avatar\":null,\"wallpaper\":null}", "{\"biography\":null}", "{\"biography\":\"\"}", "{\"displayName\":\"  A9!🎵  \"}"})
    void handlesOmittedFieldsAndExplicitClearing(String input) throws Exception {
        mvc.perform(put("/api/profile/me").header("Authorization", bearer()).contentType("application/json").content(input))
                .andExpect(status().isOk());
        User saved = users.findById(actor.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(input.contains("displayName") ? "A9!🎵" : "D".repeat(60), saved.getDisplayName());
        org.junit.jupiter.api.Assertions.assertEquals(input.contains("biography") ? null : "Profile bio", saved.getBiography());
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/avatar", saved.getAvatar());
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/cover", saved.getWallpaper());
    }

    // Invalid text and malformed payloads must never partially change the other field.
    @ParameterizedTest @ValueSource(strings = {"null", "\"\"", "\"   \"", "\"\\u00a0\\u2003\"", "123", "true", "[]", "{}",
            "too-long-name", "too-long-bio", "numeric-bio", "malformed", "null-body", "empty-body"})
    void rejectsInvalidEditsAtomically(String value) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String input = switch (value) {
            case "too-long-name" -> json.writeValueAsString(java.util.Map.of("displayName", "🎵".repeat(61), "biography", "changed"));
            case "too-long-bio" -> json.writeValueAsString(java.util.Map.of("displayName", "changed", "biography", "🎧".repeat(151)));
            case "malformed" -> "{";
            case "null-body" -> "null";
            case "empty-body" -> "";
            case "numeric-bio" -> "{\"displayName\":\"changed\",\"biography\":123}";
            default -> "{\"displayName\":" + value + ",\"biography\":\"changed\"}";
        };
        mvc.perform(put("/api/profile/me").header("Authorization", bearer()).contentType("application/json").content(input))
                .andExpect(status().isBadRequest());
        User saved = users.findById(actor.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("D".repeat(60), saved.getDisplayName());
        org.junit.jupiter.api.Assertions.assertEquals("Profile bio", saved.getBiography());
    }

    // PUT goes through the actual authentication filter, including account eligibility checks.
    @Test
    void rejectsUnauthenticatedEdits() throws Exception {
        mvc.perform(put("/api/profile/me").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/profile/me").header("Authorization", "Bearer " + jwt.generateRegistrationToken(actor.getId().toString()))
                .contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        String access = bearer(); actor.setActive(false); users.saveAndFlush(actor);
        mvc.perform(put("/api/profile/me").header("Authorization", access).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        actor.setActive(true); actor.setVerified(false); users.saveAndFlush(actor);
        mvc.perform(put("/api/profile/me").header("Authorization", access).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // A direção do visitante é independente da relação inversa e das relações de terceiros.
    @Test
    void readsDirectionalRelationshipsAndCounts() throws Exception {
        User target = account("target"), third = account("third");
        var db = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class));
        db.update("insert into user_following (follower_id, following_id) values (?, ?), (?, ?)",
                target.getId(), actor.getId(), third.getId(), target.getId());
        mvc.perform(get("/api/profile/by-handle/TARGET").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$.viewer.length()").value(3))
                .andExpect(jsonPath("$.statistics.length()").value(2)).andExpect(jsonPath("$.identity.length()").value(8))
                .andExpect(jsonPath("$.viewer.isFollowing").value(false))
                .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(1));
        db.update("insert into user_following (follower_id, following_id) values (?, ?)", actor.getId(), target.getId());
        mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(true))
                .andExpect(jsonPath("$.statistics.followers").value(2));
        for (String path : new String[]{"/me", "/by-handle/owner"})
            mvc.perform(get("/api/profile" + path).header("Authorization", bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false))
                    .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(1));
        mvc.perform(get("/api/profile/by-handle/target?actorId=" + actor.getId()).header("Authorization",
                        "Bearer " + jwt.generateAccessToken(target.getId().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false))
                .andExpect(jsonPath("$.viewer.isSelf").value(true));
        db.update("delete from user_following where follower_id = ? and following_id = ?", actor.getId(), target.getId());
        mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false))
                .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(1));
        mvc.perform(put("/api/profile/me").header("Authorization", bearer()).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false))
                .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(0));
    }

    // Mudanças de elegibilidade ocultam contagens sem apagar relações ou recriar relações removidas.
    @ParameterizedTest @ValueSource(strings = {"active", "verified"})
    void hidesIneligiblePeersAndRestoresOnlyPreservedRelationships(String column) throws Exception {
        User target = account("target"), peer = account("peer");
        var db = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class));
        db.update("insert into user_following (follower_id, following_id) values (?, ?), (?, ?)",
                peer.getId(), target.getId(), target.getId(), peer.getId());
        for (boolean eligible : new boolean[]{false, true}) {
            db.update("update users set " + column + " = ? where user_id = ?", eligible, peer.getId());
            mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.statistics.followers").value(eligible ? 1 : 0))
                    .andExpect(jsonPath("$.statistics.following").value(eligible ? 1 : 0));
            org.junit.jupiter.api.Assertions.assertEquals(2, db.queryForObject("select count(*) from user_following", Integer.class));
        }
        db.update("delete from user_following where follower_id = ?", peer.getId());
        db.update("update users set " + column + " = false where user_id = ?", peer.getId());
        db.update("update users set " + column + " = true where user_id = ?", peer.getId());
        mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statistics.followers").value(0))
                .andExpect(jsonPath("$.statistics.following").value(1));
    }

    // Uma transação concorrente não confirmada não mistura estado antigo e novo na resposta.
    @Test
    void readsCommittedSnapshotDuringEligibilityChange() throws Exception {
        User target = account("target"), peer = account("peer");
        var db = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class));
        db.update("insert into user_following (follower_id, following_id) values (?, ?), (?, ?)",
                peer.getId(), target.getId(), target.getId(), peer.getId());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                db.update("update users set active = false where user_id = ?", peer.getId());
                db.update("insert into user_following (follower_id, following_id) values (?, ?)", actor.getId(), target.getId());
                var read = pool.submit(() -> mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(false))
                        .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(1)));
                try { read.get(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            mvc.perform(get("/api/profile/by-handle/target").header("Authorization", bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.viewer.isFollowing").value(true))
                    .andExpect(jsonPath("$.statistics.followers").value(1)).andExpect(jsonPath("$.statistics.following").value(0));
        } finally { pool.shutdownNow(); }
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

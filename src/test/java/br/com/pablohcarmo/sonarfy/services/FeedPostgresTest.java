package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.controllers.FeedController;
import br.com.pablohcarmo.sonarfy.dto.FeedDto;
import br.com.pablohcarmo.sonarfy.dto.ReviewInputDto;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Compartilha apenas a configuração que exige PostgreSQL local dedicado e schema validate.
@EnabledIfSystemProperty(named = "sonarfy.test.reviews-postgres", matches = "true")
@SpringJUnitWebConfig(FeedPostgresTest.Config.class)
class FeedPostgresTest {
    @Configuration
    @Import({FeedController.class, FeedService.class, FeedCursorCodec.class, FollowingService.class})
    static class Config extends ReviewPostgresTest.Config {}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { ReviewPostgresTest.properties(r); }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate db;
    @Autowired FeedService feed;
    @Autowired ReviewService reviews;
    @Autowired FollowingService following;
    @Autowired JwtService jwt;
    @Autowired CollectionCursorCodec collectionCursors;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    long actor, followed, inverse;
    JsonMapper json = new JsonMapper();

    @BeforeEach void prepare() {
        db.update("DELETE FROM listens"); db.update("DELETE FROM tracks"); db.update("DELETE FROM reviews");
        db.update("DELETE FROM albums"); db.update("DELETE FROM artists"); db.update("DELETE FROM users");
        actor = account("actor"); followed = account("followed"); inverse = account("inverse");
        db.update("INSERT INTO artists (spotify_id, name) VALUES ('artist', 'Artist')");
        db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, 'album', 'Album' FROM artists");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // Histórico anterior ao follow entra; relação inversa não concede acesso nem duplica autoria própria.
    @Test void scopeHistoryAndPublicContract() throws Exception {
        long old = review(followed, "album", 10); long own = review(actor, "album", 0); review(inverse, "album", 5);
        following.change(inverse, actor, true);
        assertEquals(List.of(own), ids(feed.list(actor, 20, null)));
        following.change(actor, followed, true); following.change(followed, actor, true);
        db.update("UPDATE reviews SET created_at = '2026-01-01T00:00:00Z'");
        assertEquals(List.of(own, old), ids(feed.list(actor, 50, null)));
        mvc.perform(get("/api/feed").header("Authorization", token(actor)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.items[0].length()").value(7)).andExpect(jsonPath("$.items[0].author.length()").value(4))
                .andExpect(jsonPath("$.items[0].author.handle").value("actor"))
                .andExpect(jsonPath("$.items[0].rating").value(0)).andExpect(jsonPath("$.items[0].reviewText").isEmpty())
                .andExpect(jsonPath("$.items[1].rating").value(10)).andExpect(jsonPath("$.items[1].album.artist.name").value("Artist"));
        following.change(actor, followed, false);
        assertEquals(List.of(own), ids(feed.list(actor, 20, null)));
    }

    // Vazio é sucesso; elegibilidade altera itens e contagem sem exigir Spotify.
    @Test void emptyAndEligibility() throws Exception {
        assertTrue(feed.list(actor, 20, null).items().isEmpty());
        assertEquals(0, feed.list(actor, 20, null).followingCount());
        following.change(actor, followed, true);
        assertEquals(1, feed.list(actor, 20, null).followingCount());
        assertNull(feed.list(actor, 20, null).nextCursor());
        review(followed, "album", 8.5);
        for (String column : List.of("active", "verified")) {
            db.update("UPDATE users SET " + column + " = false WHERE user_id = ?", followed);
            assertTrue(feed.list(actor, 20, null).items().isEmpty());
            assertEquals(0, feed.list(actor, 20, null).followingCount());
            db.update("UPDATE users SET " + column + " = true WHERE user_id = ?", followed);
            db.update("UPDATE users SET " + column + " = false WHERE user_id = ?", actor);
            mvc.perform(get("/api/feed").header("Authorization", token(actor))).andExpect(status().isUnauthorized());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> feed.list(actor, 20, null));
            db.update("UPDATE users SET " + column + " = true WHERE user_id = ?", actor);
        }
        mvc.perform(get("/api/feed")).andExpect(status().isUnauthorized());
        for (String invalid : List.of("Bearer invalid", "Bearer " + jwt.generateRegistrationToken("" + actor), token(Long.MAX_VALUE)))
            mvc.perform(get("/api/feed").header("Authorization", invalid)).andExpect(status().isUnauthorized());
        db.update("DELETE FROM users WHERE user_id = ?", followed);
        assertEquals(0, feed.list(actor, 20, null).followingCount());
    }

    // Keyset global percorre todos os autores, permite trocar limit e isola domínios e visitantes.
    @Test void paginationAndInvalidCursors() throws Exception {
        following.change(actor, followed, true);
        var expected = new ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, ?, 'Album' FROM artists", "a" + i);
            expected.add(review(i % 2 == 0 ? actor : followed, "a" + i, 8.5));
        }
        Collections.reverse(expected); var seen = new ArrayList<Long>(); String cursor = null;
        do { var page = feed.list(actor, 2, cursor); seen.addAll(ids(page)); cursor = page.nextCursor(); } while (cursor != null);
        assertEquals(expected, seen);
        cursor = feed.list(actor, 1, null).nextCursor();
        mvc.perform(get("/api/feed").param("cursor", cursor).header("Authorization", token(followed)))
                .andExpect(status().isBadRequest());
        for (String value : List.of("", "invalid", "x".repeat(513), cursor.substring(1),
                collectionCursors.encode("" + actor, new CollectionCursorCodec.Position(1, 0)),
                new FeedCursorCodec("other-secret").encode(actor, 1)))
            mvc.perform(get("/api/feed").param("cursor", value).header("Authorization", token(actor))).andExpect(status().isBadRequest());
        for (String value : List.of("0", "51", "abc", "1.5", "9223372036854775808"))
            mvc.perform(get("/api/feed").param("limit", value).header("Authorization", token(actor))).andExpect(status().isBadRequest());
        assertEquals(expected.subList(1, 5), ids(feed.list(actor, 50, cursor)));
    }

    // Edição não reposiciona; exclusão da fronteira, inserção e mudanças de relação não invalidam o cursor.
    @Test void changesBetweenPages() {
        long low = review(followed, "album", 0); long own = review(actor, "album", 10); long hidden = review(inverse, "album", 5);
        following.change(actor, followed, true);
        var first = feed.list(actor, 1, null);
        reviews.replace(followed, low, json.readValue("{\"rating\":8.5,\"reviewText\":\"Edited\"}", ReviewInputDto.class));
        assertEquals(List.of(own, low), ids(feed.list(actor, 20, null)));
        reviews.delete(actor, own); long fresh = review(actor, "album", 5);
        following.change(actor, inverse, true);
        assertEquals(List.of(fresh, hidden, low), ids(feed.list(actor, 20, null)));
        assertEquals(List.of(low), ids(feed.list(actor, 20, first.nextCursor())));
        following.change(actor, followed, false);
        assertTrue(feed.list(actor, 20, first.nextCursor()).items().isEmpty());
        following.change(actor, followed, true);
        assertEquals("Edited", feed.list(actor, 20, first.nextCursor()).items().getFirst().reviewText());
        reviews.delete(followed, low);
        assertTrue(feed.list(actor, 20, first.nextCursor()).items().isEmpty());
    }

    // Um lock controlado pausa a consulta após abrir o snapshot, antes de ler os itens.
    @Test void consistentSnapshotUnderConcurrentChanges() throws Exception {
        review(followed, "album", 5); following.change(actor, followed, true);
        for (boolean deactivate : List.of(false, true)) {
            var pool = Executors.newSingleThreadExecutor(); var pending = new ArrayList<Future<FeedDto>>();
            try {
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    db.execute("LOCK TABLE albums IN ACCESS EXCLUSIVE MODE");
                    pending.add(pool.submit(() -> feed.list(actor, 20, null)));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); int waiting;
                    do { db.execute("SELECT pg_stat_clear_snapshot()"); waiting = db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
                    } while (waiting == 0 && System.nanoTime() < deadline);
                    assertTrue(waiting > 0); assertFalse(pending.getFirst().isDone());
                    if (deactivate) db.update("UPDATE users SET active = false WHERE user_id IN (?, ?)", actor, followed);
                    else db.update("DELETE FROM user_following WHERE follower_id = ?", actor);
                });
                var page = pending.getFirst().get(10, TimeUnit.SECONDS);
                assertEquals(1, page.followingCount()); assertEquals(1, page.items().size());
                if (!deactivate) { assertTrue(feed.list(actor, 20, null).items().isEmpty()); following.change(actor, followed, true); }
                else assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> feed.list(actor, 20, null));
            } finally { pool.shutdownNow(); }
        }
    }
    private List<Long> ids(FeedDto page) { return page.items().stream().map(FeedDto.Item::reviewId).toList(); }
    private String token(long id) { return "Bearer " + jwt.generateAccessToken("" + id); }
    private long review(long owner, String album, double rating) {
        return reviews.create(owner, album, json.readValue("{\"rating\":" + rating + "}", ReviewInputDto.class)).reviewId();
    }
    private long account(String handle) {
        return db.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash,
                                   city, country, birth_date, active, verified)
                SELECT permission_id, 'Private', 'Name', ?, ?, ?, 'private-hash', 'City', 'Country', '2000-01-01', true, true
                FROM permissions WHERE permission = 'ROLE_USER' RETURNING user_id
                """, Long.class, handle, handle, handle + "@example.com");
    }
}

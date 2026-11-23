package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.CorsConfig;
import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.controllers.EchoesController;
import br.com.pablohcarmo.sonarfy.dto.SpotifyAccessCredential;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.*;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Cadeia real Security + Controller + EchoQueryService + PostgreSQL descartável.
@EnabledIfSystemProperty(named = "sonarfy.test.echoes-postgres", matches = "true")
@SpringJUnitWebConfig(EchoQueryPostgresTest.Config.class)
class EchoQueryPostgresTest {

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class,
            SpotifyConnectionService.class, SpotifyTokenEncryptionService.class,
            EchoImportWriter.class, EchoCursorCodec.class,
            EchoQueryService.class, EchoesController.class})
    static class Config extends EchoesPersistencePostgresTest.Config {
        @Bean EchoSyncService echoSyncService() { return mock(EchoSyncService.class); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("sonarfy.echoes.test.jdbc-url",
                System.getenv().getOrDefault("SONARFY_ECHOES_TEST_JDBC_URL", ""));
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/sonarfy_echoes_validation")) {
            throw new IllegalArgumentException("A dedicated local echoes validation database is required.");
        }
        registry.add("sonarfy.test.jdbc-url", () -> url);
        registry.add("sonarfy.test.db-user", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_USER", "postgres"));
        registry.add("sonarfy.test.db-password", () -> System.getenv().getOrDefault("SONARFY_TEST_DB_PASSWORD", ""));
        registry.add("sonarfy.test.schema-mode", () -> "validate");
        registry.add("app.frontend-url", () -> "http://localhost:3000");
        registry.add("app.cors.allowed-origins", () -> "http://localhost:63342");
        registry.add("api.security.token.secret", () -> "echoes-query-pg-secret");
        registry.add("spring.mail.username", () -> "noreply@example.com");
        registry.add("spotify_token_encryption_key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired WebApplicationContext context;
    @Autowired EchoImportWriter writer;
    @Autowired SpotifyConnectionService connections;
    @Autowired SpotifyConnectionRepository connectionRepo;
    @Autowired UserRepository users;
    @Autowired EchoSyncService syncService;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private long userOneId;
    private long userTwoId;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM Listens");
        jdbc.update("DELETE FROM Track_Artists");
        jdbc.update("DELETE FROM Tracks");
        jdbc.update("DELETE FROM Albums");
        jdbc.update("DELETE FROM Artists");
        jdbc.update("DELETE FROM users WHERE email IN ('q1@example.com', 'q2@example.com')");
        userOneId = insertUser("qone", "q1@example.com");
        userTwoId = insertUser("qtwo", "q2@example.com");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private long insertUser(String handle, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email,
                    password_hash, city, country, birth_date, verified)
                VALUES ((SELECT permission_id FROM permissions WHERE permission = 'ROLE_USER'),
                    'Query', 'User', 'Query User', ?, ?,
                    'test-hash', 'Brasilia', 'Brasil', '2000-01-01', true) RETURNING user_id
                """, Long.class, handle, email);
    }

    @Test
    void returnsEmptyHistoryWithoutCreatingSyncStateAndRejectsInvalidLimits() throws Exception {
        String token = jwt.generateAccessToken(Long.toString(userOneId));
        mvc.perform(get("/api/echoes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.sync.lastSucceededAt").isEmpty())
                .andExpect(jsonPath("$.sync.historyStartsAt").isEmpty())
                .andExpect(jsonPath("$.sync.nextAllowedAt").isEmpty());

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM echo_sync_state WHERE user_id = ?", Integer.class, userOneId));
        mvc.perform(get("/api/echoes").header("Authorization", "Bearer " + token).param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/echoes").header("Authorization", "Bearer " + token).param("limit", "51"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(syncService);
    }

    @Test
    void paginatesWithTiesRepeatedTracksOrderedArtistsAndWorksAfterDisconnect() throws Exception {
        long version = users.findById(userOneId).orElseThrow().getSpotifyAuthorizationVersion();
        connections.connect(userOneId, version,
                new SpotifyTokenResponseDto("acc", "Bearer", 3600L, "ref", "user-read-recently-played"));
        var row = connectionRepo.findByUserId(userOneId).orElseThrow();
        var cred = new SpotifyAccessCredential("acc", row.getId(),
                row.getAuthorizationGeneration(), version, row.getScopes());

        var albumNoCover = new AlbumObject("albNoCover", "Album No Cover", "2024", "year", List.of(),
                List.of(new ArtistObject("art2", "Second Artist")));
        var tTie = OffsetDateTime.parse("2026-11-22T18:00:00.123456Z");
        var tOlder = OffsetDateTime.parse("2026-11-22T17:00:00Z");
        var items = List.of(
                new PlayHistoryItem(new TrackObject("trk1", "Track One", 210000, true, false, "track",
                        albumNoCover, List.of(new ArtistObject("art2", "Second"), new ArtistObject("art1", "First"))),
                        tTie),
                new PlayHistoryItem(new TrackObject("trk2", "Track Two", 180000, false, false, "track",
                        albumNoCover, List.of(new ArtistObject("art1", "First"))),
                        tTie),
                new PlayHistoryItem(new TrackObject("trk1", "Track One", 210000, true, false, "track",
                        albumNoCover, List.of(new ArtistObject("art2", "Second"), new ArtistObject("art1", "First"))),
                        tOlder)
        );
        writer.writeBatch(userOneId, cred, items, OffsetDateTime.parse("2026-11-22T18:05:00Z"));
        connections.disconnect(userOneId);

        String tokenOne = jwt.generateAccessToken(Long.toString(userOneId));
        String tokenTwo = jwt.generateAccessToken(Long.toString(userTwoId));

        // Página 1 (limit=2): não multiplica linhas por vários artistas.
        var page1Raw = mvc.perform(get("/api/echoes")
                        .header("Authorization", "Bearer " + tokenOne)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].track.spotifyId").value("trk2"))
                .andExpect(jsonPath("$.items[1].track.spotifyId").value("trk1"))
                .andExpect(jsonPath("$.items[1].artists.length()").value(2))
                .andExpect(jsonPath("$.items[1].artists[0].spotifyId").value("art2"))
                .andExpect(jsonPath("$.items[1].artists[1].spotifyId").value("art1"))
                .andExpect(jsonPath("$.items[0].album.coverUrl").isEmpty())
                .andReturn().getResponse().getContentAsString();

        var page1 = json.readTree(page1Raw);
        String cursor1 = page1.get("nextCursor").asText();
        assertNotNull(cursor1);

        // Cursor do usuário 1 é rejeitado para o usuário 2.
        mvc.perform(get("/api/echoes")
                        .header("Authorization", "Bearer " + tokenTwo)
                        .param("cursor", cursor1))
                .andExpect(status().isBadRequest());

        // Usuário 2 permanece isolado com 0 escutas.
        mvc.perform(get("/api/echoes").header("Authorization", "Bearer " + tokenTwo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // Página 2: retorna a terceira escuta (mesma faixa em instante anterior) e nextCursor null.
        var page2Raw = mvc.perform(get("/api/echoes")
                        .header("Authorization", "Bearer " + tokenOne)
                        .param("limit", "2")
                        .param("cursor", cursor1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].track.spotifyId").value("trk1"))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andReturn().getResponse().getContentAsString();

        var page2 = json.readTree(page2Raw);
        assertNotEquals(page1.get("items").get(0).get("id").asLong(), page2.get("items").get(0).get("id").asLong());
        assertNotEquals(page1.get("items").get(1).get("id").asLong(), page2.get("items").get(0).get("id").asLong());
        verifyNoInteractions(syncService);
    }

    // Executa os erros de autenticação e entrada pela cadeia real de segurança.
    @Test
    void rejectsMissingWrongPurposeInactiveAndUnverifiedCredentialsAndInvalidCursors() throws Exception {
        mvc.perform(get("/api/echoes")).andExpect(status().isUnauthorized());
        for (String token : List.of("invalid", jwt.generateRegistrationToken(Long.toString(userOneId)))) {
            mvc.perform(get("/api/echoes").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        String bearer = "Bearer " + jwt.generateAccessToken(Long.toString(userOneId));
        for (String cursor : List.of("tampered", "", "null")) {
            mvc.perform(get("/api/echoes").header("Authorization", bearer).param("cursor", cursor))
                    .andExpect(status().isBadRequest());
        }
        jdbc.update("UPDATE users SET active = false WHERE user_id = ?", userOneId);
        mvc.perform(get("/api/echoes").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET active = true, verified = false WHERE user_id = ?", userOneId);
        mvc.perform(get("/api/echoes").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        verifyNoInteractions(syncService);
    }

    // Duas contas autenticadas mantêm históricos próprios e rejeitam o cursor da outra conta.
    @Test
    void isolatesTwoNonEmptyHistoriesAndRejectsForeignCursor() throws Exception {
        importHistory(userOneId, 3);
        importHistory(userTwoId, 2);
        var first = readPage(userOneId, null);
        var second = readPage(userTwoId, null);
        assertEquals(2, first.get("items").size());
        assertEquals(2, second.get("items").size());
        var firstIds = new java.util.HashSet<Long>();
        first.get("items").forEach(item -> firstIds.add(item.get("id").asLong()));
        second.get("items").forEach(item -> assertFalse(firstIds.contains(item.get("id").asLong())));
        String foreignCursor = first.get("nextCursor").asText();
        mvc.perform(get("/api/echoes").header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(userTwoId)))
                        .param("cursor", foreignCursor))
                .andExpect(status().isBadRequest());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userOneId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM Listens WHERE user_id = ?", Integer.class, userTwoId));
        verifyNoInteractions(syncService);
    }

    // Percorre 51 escutas até nextCursor nulo, sem copiar valores manualmente nem repetir IDs.
    @Test
    void walksEveryPageWithTiesAndStopsAtNullCursor() throws Exception {
        importHistory(userOneId, 51);
        var seen = new java.util.HashSet<Long>();
        String cursor = null;
        OffsetDateTime previousTime = null;
        long previousId = Long.MAX_VALUE;
        int pages = 0;
        do {
            var page = readPage(userOneId, cursor);
            for (var item : page.get("items")) {
                long id = item.get("id").asLong();
                var time = OffsetDateTime.parse(item.get("playedAt").asText());
                assertTrue(seen.add(id), "Escuta repetida entre páginas");
                if (previousTime != null) {
                    assertTrue(time.isBefore(previousTime) || (time.isEqual(previousTime) && id < previousId));
                }
                previousTime = time;
                previousId = id;
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            assertTrue(++pages <= 26, "A paginação deve terminar");
        } while (cursor != null);
        assertEquals(51, seen.size());
        assertEquals(26, pages);
        verifyNoInteractions(syncService);
    }

    // Agrupa antes do cursor, conta escutas antigas e isola histórico e usuários.
    @Test
    void groupsAlbumsBeforePagingAndPaginatesAlbumHistory() throws Exception {
        importHistory(userOneId, 5);
        importHistory(userTwoId, 2);
        jdbc.update("INSERT INTO Albums (artist_id, spotify_id, name) SELECT artist_id, 'edition', 'Other Edition' FROM Albums WHERE spotify_id = 'fixtureAlbum'");
        jdbc.update("UPDATE Tracks SET album_id = (SELECT album_id FROM Albums WHERE spotify_id = 'edition') WHERE spotify_id = 'fixtureTrack1'");
        String bearer = "Bearer " + jwt.generateAccessToken(Long.toString(userOneId));
        var first = mvc.perform(get("/api/echoes/albums").header("Authorization", bearer).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].album.spotifyId").value("edition"))
                .andExpect(jsonPath("$.items[0].listenCount").value(2))
                .andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(first).get("nextCursor").asText();
        mvc.perform(get("/api/echoes/albums").header("Authorization", bearer).param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].album.spotifyId").value("fixtureAlbum"))
                .andExpect(jsonPath("$.items[0].listenCount").value(3))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        var history = mvc.perform(get("/api/echoes/albums/fixtureAlbum/listens")
                        .header("Authorization", bearer).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].track.spotifyId").value("fixtureTrack0"))
                .andReturn().getResponse().getContentAsString();
        String historyCursor = json.readTree(history).get("nextCursor").asText();
        mvc.perform(get("/api/echoes/albums/fixtureAlbum/listens").header("Authorization", bearer)
                        .param("cursor", historyCursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        for (String path : List.of("/api/echoes", "/api/echoes/albums", "/api/echoes/albums/edition/listens")) {
            mvc.perform(get(path).header("Authorization", bearer).param("cursor", historyCursor))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/echoes/albums").header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(userTwoId)))
                        .param("cursor", cursor)).andExpect(status().isBadRequest());
        for (String path : List.of("/api/echoes/albums", "/api/echoes/albums/missing/listens")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", bearer).param("limit", "0"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/echoes/albums/missing/listens").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        verifyNoInteractions(syncService);
    }

    private com.fasterxml.jackson.databind.JsonNode readPage(long userId, String cursor) throws Exception {
        var request = get("/api/echoes").param("limit", "2")
                .header("Authorization", "Bearer " + jwt.generateAccessToken(Long.toString(userId)));
        if (cursor != null) request.param("cursor", cursor);
        var response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private void importHistory(long userId, int count) {
        long version = users.findById(userId).orElseThrow().getSpotifyAuthorizationVersion();
        connections.connect(userId, version, new SpotifyTokenResponseDto("fixture-access", "Bearer", 3600L,
                "fixture-refresh", "user-read-recently-played"));
        var connection = connectionRepo.findByUserId(userId).orElseThrow();
        var credential = new SpotifyAccessCredential("fixture-access", connection.getId(),
                connection.getAuthorizationGeneration(), version, connection.getScopes());
        var artists = List.of(new ArtistObject("fixtureArtist", "Fixture Artist"));
        var album = new AlbumObject("fixtureAlbum", "Fixture Album", "2024", "year", List.of(), artists);
        var time = OffsetDateTime.parse("2026-11-23T00:00:00.123456Z");
        var items = java.util.stream.IntStream.range(0, count).mapToObj(index -> new PlayHistoryItem(
                new TrackObject("fixtureTrack" + (index % 2), "Fixture Track", 1000, false, false, "track", album, artists),
                time.minusSeconds(index / 2))).toList();
        writer.writeBatch(userId, credential, items, time.plusMinutes(1));
    }
}

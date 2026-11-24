package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.controllers.AlbumController;
import br.com.pablohcarmo.sonarfy.entities.User;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.method.support.*;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Controller e servico reais com Feign contra a simulacao Postman; conta local sem conexao Spotify.
@EnabledIfSystemProperty(named = "sonarfy.test.spotify-api-sim", matches = "true")
@SpringJUnitConfig(SpotifyRecentlyPlayedSimulationTest.Config.class)
class AlbumSearchSimulationTest {
    @Autowired SpotifyApiClient client;
    private SpotifyCatalogTokenService tokens;
    private JdbcTemplate db;
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spotify.api-url", () -> "http://127.0.0.1:55442/spotify-api");
    }

    @BeforeEach
    void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:album_search;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE IF NOT EXISTS users(user_id BIGINT PRIMARY KEY, active BOOLEAN, verified BOOLEAN)");
        db.update("MERGE INTO users KEY(user_id) VALUES (1, true, true)");
        tokens = mock(SpotifyCatalogTokenService.class);
        when(tokens.get()).thenReturn("token-ok");
        var service = new AlbumSearchService(client, tokens, db, "BR");
        mvc = MockMvcBuilders.standaloneSetup(new AlbumController(mock(AlbumDetailsService.class), service))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.hasParameterAnnotation(AuthenticationPrincipal.class); }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) {
                        var user = new User(); user.setId(1L); return user;
                    }
                }).build();
    }

    // Acentos, operadores e Unicode chegam como texto; pagina nunca expoe URL externa.
    @Test
    void searchesAndPaginatesWithoutPersisting() throws Exception {
        mvc.perform(get("/api/albums/search").param("q", "\u00a0Daft & artist:Bjork\u2003"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].spotifyId").value("4aawyAB9vmqN3uQ7FjRGTy"))
                .andExpect(jsonPath("$.items[0].releasePrecision").value("mes"))
                .andExpect(jsonPath("$.items[0].totalTracks").isEmpty())
                .andExpect(jsonPath("$.nextOffset").value(10));
        mvc.perform(get("/api/albums/search").param("q", "album").param("offset", "1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextOffset").isEmpty());
        mvc.perform(get("/api/albums/search").param("q", "empty"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.nextOffset").isEmpty());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM users", Integer.class));
    }

    // Entrada invalida e conta inelegivel nao chegam ao cache de credenciais.
    @Test
    void rejectsBeforeProviderAccess() throws Exception {
        for (String q : new String[]{"", "a", "\u00a0\u2003", "a\0b", "x".repeat(101)})
            mvc.perform(get("/api/albums/search").param("q", q)).andExpect(status().isBadRequest());
        for (String limit : new String[]{"0", "11", "x"})
            mvc.perform(get("/api/albums/search").param("q", "ok").param("limit", limit)).andExpect(status().isBadRequest());
        for (String offset : new String[]{"-1", "1001", "x"})
            mvc.perform(get("/api/albums/search").param("q", "ok").param("offset", offset)).andExpect(status().isBadRequest());
        db.update("UPDATE users SET verified = false");
        mvc.perform(get("/api/albums/search").param("q", "ok")).andExpect(status().isUnauthorized());
        verifyNoInteractions(tokens);
    }

    // Erros HTTP e JSON invalidos sao sanitizados; 401 invalida somente o token usado sem retry.
    @Test
    void translatesFailures() throws Exception {
        String[] queries = {"401", "403", "404", "429", "500", "503", "malformed", "invalid", "timeout"};
        int[] statuses = {502, 502, 502, 503, 503, 503, 502, 502, 504};
        for (int i = 0; i < queries.length; i++) {
            var result = mvc.perform(get("/api/albums/search").param("q", queries[i]))
                    .andExpect(status().is(statuses[i])).andExpect(content().string("")).andReturn();
            assertEquals(queries[i].equals("429") ? "900" : null, result.getResponse().getHeader("Retry-After"));
        }
        verify(tokens).invalidate("token-ok");
        verify(tokens, times(queries.length)).get();
    }
}

package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.services.SpotifyAuthorizationService;
import br.com.pablohcarmo.sonarfy.services.SpotifyAuthorizationStateService;
import br.com.pablohcarmo.sonarfy.services.SpotifyCallbackService;
import br.com.pablohcarmo.sonarfy.services.SpotifyConnectionService;
import br.com.pablohcarmo.sonarfy.services.AuthSpotifyService;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException.Category;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.time.OffsetDateTime;
import br.com.pablohcarmo.sonarfy.dto.SpotifyConnectionResponseDto;
import br.com.pablohcarmo.sonarfy.enums.SpotifyConnectionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Usa segurança, controller, state e callback reais; banco e troca de tokens simulados.
@SpringJUnitWebConfig(SpotifyControllerTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=spotify-controller-test-secret",
        "SPOTIFY_CLIENT_ID=test-client", "SPOTIFY_REDIRECT_URI=http://127.0.0.1:8081/api/spotify/callback",
        "SPOTIFY_FRONTEND_REDIRECT_URI=http://localhost:3000/settings/spotify"})
class SpotifyControllerTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, SpotifyController.class, JwtService.class,
            SpotifyAuthorizationService.class, SpotifyAuthorizationStateService.class, SpotifyCallbackService.class})
    static class Config {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean AuthSpotifyService tokens() { return mock(AuthSpotifyService.class); }
        @Bean SpotifyConnectionService connections() { return mock(SpotifyConnectionService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired SpotifyAuthorizationStateService states;
    @Autowired AuthSpotifyService tokens;
    @Autowired SpotifyConnectionService connections;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        reset(users, tokens, connections);
        pending().clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = new User();
        user.setId(42L);
        user.setEmail("user@example.com");
        user.setHandle("test-handle");
        user.setActive(true);
        user.setVerified(true);
        Permission permission = new Permission();
        permission.setName("ROLE_USER");
        user.setPermission(permission);
        when(users.findWithPermissionById(42L)).thenReturn(Optional.of(user));
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findById(42L)).thenReturn(Optional.of(user));
    }

    @Test
    void shouldRejectMissingInvalidAndRegistrationBearerWithoutCreatingState() throws Exception {
        mvc.perform(post("/api/spotify/authorization")).andExpect(status().isUnauthorized());
        for (String token : new String[]{"invalid", jwt.generateRegistrationToken("42")}) {
            mvc.perform(post("/api/spotify/authorization").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        assertTrue(pending().isEmpty());
        verify(users, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void shouldReturnUrlAndAssociateStateWithAuthenticatedUserIgnoringSuppliedId() throws Exception {
        var result = mvc.perform(post("/api/spotify/authorization")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken("42"))
                        .param("userId", "99").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.authorizationUrl").isString()).andReturn();

        String url = new ObjectMapper().readTree(result.getResponse().getContentAsString())
                .get("authorizationUrl").asText();
        var uri = UriComponentsBuilder.fromUriString(url).build();
        assertEquals("https", uri.getScheme());
        assertEquals("accounts.spotify.com", uri.getHost());
        assertEquals("/authorize", uri.getPath());
        String state = uri.getQueryParams().getFirst("state");
        assertNotNull(state);
        assertEquals(1, pending().size());
        assertEquals(42L, ReflectionTestUtils.getField(pending().get(state), "userId"));
        verify(users).findByEmailIgnoreCase("user@example.com");
    }

    @Test
    void shouldReturn429OnFourthAttemptWithoutBlockingAnotherUser() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/spotify/authorization").header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/spotify/authorization").header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isTooManyRequests());
        assertEquals(3, pending().size());

        User other = new User();
        other.setId(43L);
        other.setEmail("other@example.com");
        other.setActive(true);
        other.setVerified(true);
        other.setPermission(user.getPermission());
        when(users.findWithPermissionById(43L)).thenReturn(Optional.of(other));
        when(users.findByEmailIgnoreCase(other.getEmail())).thenReturn(Optional.of(other));
        when(users.findById(43L)).thenReturn(Optional.of(other));
        mvc.perform(post("/api/spotify/authorization").header("Authorization", "Bearer " + jwt.generateAccessToken("43")))
                .andExpect(status().isOk());
        assertEquals(4, pending().size());
    }

    @Test
    void shouldCompleteCallbackWithoutBearerAndRejectReplay() throws Exception {
        // Inicia com Bearer, depois simula o retorno do navegador sem Authorization.
        var start = mvc.perform(post("/api/spotify/authorization")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isOk()).andReturn();
        String url = new ObjectMapper().readTree(start.getResponse().getContentAsString())
                .get("authorizationUrl").asText();
        String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
        assertNotNull(state);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenReturn(
                new SpotifyTokenResponseDto("test-access", "Bearer", 3600L, "test-refresh", "user-library-read"));

        var callback = mvc.perform(get("/api/spotify/callback")
                        .param("code", "test-code")
                        .param("state", state)
                        .param("redirect_uri", "https://attacker.example/steal"))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=connected"))
                .andExpect(content().string("")).andReturn();
        assertNull(callback.getRequest().getSession(false));
        mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=invalid_state"));
        assertTrue(pending().isEmpty());
        verify(users, times(2)).findById(42L);
        verify(tokens, times(1)).exchangeCode("test-code");
    }

    @ParameterizedTest
    @CsvSource({"test-code,,", "test-code,unknown,", ",,", "test-code,known,access_denied", ",known,unknown-error"})
    void shouldRejectInvalidCallbackWithoutTokenExchange(String code, String stateValue, String error) throws Exception {
        String validState = states.create(42L, 0L);
        var callback = get("/api/spotify/callback");
        if (code != null) callback.param("code", code);
        if (stateValue != null) callback.param("state", stateValue.equals("known") ? validState : stateValue);
        if (error != null) callback.param("error", error);

        mvc.perform(callback)
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=invalid_state"));
        verify(users, never()).findById(anyLong());
        verifyNoInteractions(tokens);
        assertEquals(42L, states.consume(validState).userId());
    }

    @Test
    void shouldConsumeDenialWithoutBearerOrTokenExchange() throws Exception {
        String state = states.create(42L, 0L);
        when(users.findById(42L)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/spotify/callback").param("error", "access_denied").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=denied"));
        mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=invalid_state"));
        verifyNoInteractions(tokens);
        assertTrue(pending().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "UNAVAILABLE", "TIMEOUT"})
    void shouldRedirectToErrorOnExchangeFailureAndPreventRetry(Category category) throws Exception {
        String state = states.create(42L, 0L);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenThrow(new SpotifyIntegrationException(category));

        mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=error"));
        mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=invalid_state"));
        verify(tokens, times(1)).exchangeCode("test-code");
        assertTrue(pending().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/spotify/callback", "PUT,/api/spotify/callback", "PATCH,/api/spotify/callback",
            "DELETE,/api/spotify/callback", "HEAD,/api/spotify/callback", "OPTIONS,/api/spotify/callback",
            "GET,/api/spotify/callback/extra", "GET,/api/spotify/authorization", "POST,/api/spotify/authorization"})
    void shouldKeepOtherSpotifyRequestsProtected(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(tokens);
        assertTrue(pending().isEmpty());
    }

    @Test
    void shouldRedirectToErrorForIncompleteTokenResponseAndRejectReplay() throws Exception {
        String state = states.create(42L, 0L);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenReturn(
                new SpotifyTokenResponseDto(null, "Bearer", 3600L, "private-refresh", null));

        var result = mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=error"))
                .andReturn();
        assertFalse(result.getResponse().getHeader("Location").contains("private-refresh"));
        assertFalse(result.getResponse().getContentAsString().contains("private-refresh"));
        mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=invalid_state"));
        verify(tokens, times(1)).exchangeCode("test-code");
        assertTrue(pending().isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, 3601L, Long.MAX_VALUE})
    void shouldRedirectToErrorForInvalidExpiryWithoutPersistingAndRejectReplay(Long expiresIn) throws Exception {
        String state = states.create(42L, 0L);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenReturn(
                new SpotifyTokenResponseDto("private-access", "Bearer", expiresIn, "private-refresh", null));

        var result = mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=error"))
                .andReturn();
        assertFalse(result.getResponse().getHeader("Location").contains("private-access"));
        mvc.perform(get("/api/spotify/callback").param("code", "test-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:3000/settings/spotify?spotify=invalid_state"));
        verify(tokens, times(1)).exchangeCode("test-code");
        verifyNoInteractions(connections);
        assertTrue(pending().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://app.sonarfy.com/settings/spotify",
            "https://app.sonarfy.com:8443/settings?tab=integrations",
            "http://localhost:3000/callback",
            "http://127.0.0.1:5173/callback",
            "http://[::1]:8080/callback"
    })
    void shouldAcceptValidFrontendRedirectUrisAndPreserveExistingQueryParameters(String configuredUri) {
        // Verifica aceitação de HTTPS e loopback HTTP, preservando query params fixos da URI.
        var controller = new SpotifyController(users, mock(SpotifyAuthorizationService.class),
                new SpotifyCallbackService(states, users, tokens, connections), connections, configuredUri);
        String state = states.create(42L, 0L);
        when(users.findById(42L)).thenReturn(Optional.of(user));

        var response = controller.callback(null, state, "access_denied");
        assertEquals(302, response.getStatusCode().value());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertEquals("denied", UriComponentsBuilder.fromUri(location).build().getQueryParams().getFirst("spotify"));
        if (configuredUri.contains("tab=integrations")) {
            assertEquals("integrations", UriComponentsBuilder.fromUri(location).build().getQueryParams().getFirst("tab"));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "not a uri",
            "/relative/path",
            "http://app.sonarfy.com/settings/spotify",
            "ftp://localhost:3000/callback",
            "https://user:secret@app.sonarfy.com/callback",
            "https://app.sonarfy.com/callback#section",
            "https://app.sonarfy.com:0/callback",
            "https://app.sonarfy.com:70000/callback"
    })
    void shouldRejectInvalidFrontendRedirectUrisAtStartup(String invalidUri) {
        // Confirma rejeição na inicialização sem ecoar o endereço inválido na mensagem.
        var exception = assertThrows(IllegalArgumentException.class, () ->
                new SpotifyController(users, mock(SpotifyAuthorizationService.class),
                        mock(SpotifyCallbackService.class), connections, invalidUri));
        if (invalidUri != null && !invalidUri.isBlank()) {
            assertFalse(exception.getMessage().contains(invalidUri));
        }
    }

    @Test
    void connectionRequiresValidAccessBearer() throws Exception {
        mvc.perform(get("/api/spotify/connection")).andExpect(status().isUnauthorized());
        for (String token : new String[]{"invalid", jwt.generateRegistrationToken("42")}) {
            mvc.perform(get("/api/spotify/connection").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(connections, tokens);
    }

    @Test
    void connectionReturnsOnlyPublicFieldsForAuthenticatedUserIgnoringSuppliedId() throws Exception {
        var connectedAt = OffsetDateTime.parse("2026-11-01T12:00:00Z");
        when(connections.getConnection(42L)).thenReturn(
                new SpotifyConnectionResponseDto(SpotifyConnectionStatus.CONNECTED, connectedAt));

        var result = mvc.perform(get("/api/spotify/connection")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken("42"))
                        .param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.connectedAt").isString())
                .andReturn();
        var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertEquals(2, json.size());
        assertEquals(connectedAt, OffsetDateTime.parse(json.get("connectedAt").asText()));
        verify(connections).getConnection(42L);
        verifyNoMoreInteractions(connections);
        verifyNoInteractions(tokens);
        assertTrue(pending().isEmpty());
    }

    @Test
    void connectionReturnsDisconnectedWithExplicitNullDate() throws Exception {
        when(connections.getConnection(42L)).thenReturn(
                new SpotifyConnectionResponseDto(SpotifyConnectionStatus.DISCONNECTED, null));

        var result = mvc.perform(get("/api/spotify/connection")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("DISCONNECTED"))
                .andReturn();
        var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertEquals(2, json.size());
        assertTrue(json.has("connectedAt"));
        assertTrue(json.get("connectedAt").isNull());
    }

    @Test
    void connectionRejectsUserMissingAtControllerLookup() throws Exception {
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.empty());

        mvc.perform(get("/api/spotify/connection")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(connections, tokens);
    }

    @Test
    void disconnectUsesAuthenticatedUserAndReturnsNoContentEvenOnRepeat() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/spotify/connection")
                            .header("Authorization", "Bearer " + jwt.generateAccessToken("42"))
                            .param("userId", "99"))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(""));
        }
        verify(connections, times(2)).disconnect(42L);
        verifyNoMoreInteractions(connections);
    }

    @Test
    void disconnectRejectsMissingOrInvalidBearer() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/spotify/connection"))
                .andExpect(status().isUnauthorized());
        for (String token : new String[]{"invalid", jwt.generateRegistrationToken("42")}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/spotify/connection")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(connections);
    }

    private Map<?, ?> pending() {
        // Inspeciona o state sem criar uma operação pública de consulta ou consumo.
        return assertInstanceOf(Map.class, ReflectionTestUtils.getField(states, "pending"));
    }
}

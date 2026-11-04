package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.services.SpotifyAuthorizationService;
import br.com.pablohcarmo.sonarfy.services.SpotifyAuthorizationStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Usa o filtro Bearer, JWT, controller e serviços reais; apenas o banco é simulado.
@SpringJUnitWebConfig(SpotifyControllerTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=spotify-controller-test-secret",
        "SPOTIFY_CLIENT_ID=test-client", "SPOTIFY_REDIRECT_URI=http://127.0.0.1:8081/api/spotify/callback"})
class SpotifyControllerTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, SpotifyController.class, JwtService.class,
            SpotifyAuthorizationService.class, SpotifyAuthorizationStateService.class})
    static class Config {
        @Bean UserRepository users() { return mock(UserRepository.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired SpotifyAuthorizationStateService states;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        reset(users);
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
        user.setPermissionId(permission);
        when(users.findWithPermissionById(42L)).thenReturn(Optional.of(user));
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
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
        other.setPermissionId(user.getPermissionId());
        when(users.findWithPermissionById(43L)).thenReturn(Optional.of(other));
        when(users.findByEmailIgnoreCase(other.getEmail())).thenReturn(Optional.of(other));
        mvc.perform(post("/api/spotify/authorization").header("Authorization", "Bearer " + jwt.generateAccessToken("43")))
                .andExpect(status().isOk());
        assertEquals(4, pending().size());
    }

    private Map<?, ?> pending() {
        // Inspeciona o state sem criar uma operação pública de consulta ou consumo.
        return assertInstanceOf(Map.class, ReflectionTestUtils.getField(states, "pending"));
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotifyAuthorizationServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8081/api/spotify/callback",
            "https://example.com/callback?source=sonarfy&return=%2Fprofile"
    })
    void shouldBuildAuthorizationUrlPreservingParameterValues(String redirectUri) {
        var stateService = mock(SpotifyAuthorizationStateService.class);
        var users = mock(UserRepository.class);
        var user = new User();
        user.setActive(true);
        user.setVerified(true);
        user.invalidateSpotifyAuthorizations();
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(stateService.create(42L, 1L)).thenReturn("test-state");
        var service = new SpotifyAuthorizationService(stateService, users, "test-client", redirectUri);

        var uri = service.startAuthorization(42L);

        assertEquals("https", uri.getScheme());
        assertEquals("accounts.spotify.com", uri.getHost());
        assertEquals("/authorize", uri.getPath());
        var params = Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertEquals(Map.of(
                "response_type", "code", "client_id", "test-client",
                "redirect_uri", redirectUri, "scope", "user-library-read", "state", "test-state"
        ), params);
        assertNull(uri.getUserInfo());
        assertNull(uri.getFragment());
        verify(stateService).create(42L, 1L);
        verifyNoMoreInteractions(stateService);
    }
}

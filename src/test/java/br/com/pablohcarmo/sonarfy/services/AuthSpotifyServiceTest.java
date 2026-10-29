package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.AuthSpotifyClient;
import br.com.pablohcarmo.sonarfy.dto.LoginSpotifyResponseDto;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthSpotifyServiceTest {

    private static final String CLIENT_ID = "test-client";
    private static final String CLIENT_SECRET = "test-secret";
    private static final String REDIRECT_URI = "https://example.com/spotify/callback";

    @Mock
    private AuthSpotifyClient client;

    @ParameterizedTest(name = "Rejects missing credential: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void shouldRejectMissingCredentialsAtConstruction(String credential) {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSpotifyService(client, credential, CLIENT_SECRET, REDIRECT_URI));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSpotifyService(client, CLIENT_ID, credential, REDIRECT_URI));
        verifyNoInteractions(client);
    }

    @ParameterizedTest(name = "Rejects redirect URI: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "\t\n", "https://example.com/call back", "https://[::1/callback",
            "/spotify/callback", "https:///callback", "//example.com/callback",
            "http://example.com/callback", "ftp://example.com/callback",
            "http://localhost:8081/callback", "https://LOCALHOST/callback",
            "http://127.0.0.1.example.com/callback", "http://[::2]/callback",
            "https://user:secret@example.com/callback", "https://example.com/callback#fragment",
            "https://example.com:0/callback", "https://example.com:65536/callback",
            "https://example.com:abc/callback"
    })
    void shouldRejectInvalidRedirectUriAtConstruction(String redirectUri) {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new AuthSpotifyService(client, CLIENT_ID, CLIENT_SECRET, redirectUri));

        // O erro não deve expor valores eventualmente presentes na URI.
        assertFalse(exception.getMessage().contains("user:secret"));
        verifyNoInteractions(client);
    }

    @ParameterizedTest(name = "Accepts and preserves redirect URI: {0}")
    @ValueSource(strings = {
            REDIRECT_URI,
            "https://example.com:65535/callback?source=sonarfy%20test",
            "http://127.0.0.1:8081/callback",
            "http://[::1]:8081/callback"
    })
    void shouldExchangeCodeWithAcceptedRedirectUri(String redirectUri) {
        var service = new AuthSpotifyService(client, CLIENT_ID, CLIENT_SECRET, redirectUri);
        var form = Map.of("grant_type", "authorization_code",
                "code", "test-code", "redirect_uri", redirectUri);
        // Basic test-client:test-secret, usando apenas credenciais fictícias.
        String authorization = "Basic dGVzdC1jbGllbnQ6dGVzdC1zZWNyZXQ=";
        var response = new LoginSpotifyResponseDto("access", "Bearer", 3600L, "refresh");
        when(client.exchangeCode(authorization, form)).thenReturn(response);

        assertSame(response, service.exchangeCode("test-code"));

        verify(client).exchangeCode(authorization, form);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "Rejects missing authorization code: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void shouldRejectMissingCodeBeforeCallingClient(String code) {
        var service = new AuthSpotifyService(client, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);

        var exception = assertThrows(ResponseStatusException.class,
                () -> service.exchangeCode(code));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(client);
    }
}

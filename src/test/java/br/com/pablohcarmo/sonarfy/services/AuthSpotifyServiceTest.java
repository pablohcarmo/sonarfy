package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.AuthSpotifyClient;
import br.com.pablohcarmo.sonarfy.dto.LoginSpotifyResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException.Category;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.List;
import java.util.stream.Stream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

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
        var response = new LoginSpotifyResponseDto("access", "Bearer", 3600L, "refresh", "user-library-read");
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

    @ParameterizedTest
    @CsvSource({"400,REJECTED", "401,REJECTED", "403,REJECTED", "422,REJECTED",
            "429,UNAVAILABLE", "500,UNAVAILABLE", "502,UNAVAILABLE", "503,UNAVAILABLE"})
    void shouldTranslateHttpFailuresWithoutExposingExternalDetails(int status, Category category) {
        var response = Response.builder().status(status).reason("external-secret")
                .request(sensitiveRequest()).headers(Map.of())
                .body("external-secret access-token refresh-token", StandardCharsets.UTF_8).build();

        assertSanitizedFailure(FeignException.errorStatus("exchangeCode", response), category);
    }

    static Stream<Throwable> timeoutCauses() {
        return Stream.of(new SocketTimeoutException("external-secret"),
                new HttpTimeoutException("external-secret"),
                new IOException("external-secret", new SocketTimeoutException("external-secret")));
    }

    @ParameterizedTest
    @MethodSource("timeoutCauses")
    void shouldIdentifyTimeoutsIncludingWrappedCauses(Throwable cause) {
        assertSanitizedFailure(new RetryableException(-1, "external-secret", Request.HttpMethod.POST,
                cause, (Long) null, sensitiveRequest()), Category.TIMEOUT);
    }

    @Test
    void shouldTranslateConnectionFailureAsUnavailable() {
        assertSanitizedFailure(new RetryableException(-1, "external-secret", Request.HttpMethod.POST,
                new ConnectException("external-secret"), (Long) null, sensitiveRequest()),
                Category.UNAVAILABLE);
    }

    private Request sensitiveRequest() {
        return Request.create(Request.HttpMethod.POST, "https://example.com/token?code=external-secret",
                Map.of("Authorization", List.of("Basic external-secret")),
                "client_secret=external-secret".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
                new RequestTemplate());
    }

    private void assertSanitizedFailure(FeignException failure, Category category) {
        var service = new AuthSpotifyService(client, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
        when(client.exchangeCode(anyString(), anyMap())).thenThrow(failure);

        var exception = assertThrows(SpotifyIntegrationException.class,
                () -> service.exchangeCode("test-code"));

        assertEquals(category, exception.getCategory());
        assertEquals(switch (category) {
            case REJECTED -> "Spotify rejected the token exchange request.";
            case UNAVAILABLE -> "Spotify token exchange is currently unavailable.";
            case TIMEOUT -> "Spotify token exchange timed out.";
        }, exception.getMessage());
        assertNull(exception.getCause());
        var trace = new StringWriter();
        exception.printStackTrace(new PrintWriter(trace));
        for (String secret : List.of("external-secret", "access-token", "refresh-token", "FeignException")) {
            assertFalse(trace.toString().contains(secret));
        }
        verify(client).exchangeCode(anyString(), anyMap());
        verifyNoMoreInteractions(client);
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotifyRecentlyPlayedServiceTest {

    private final SpotifyApiClient client = mock(SpotifyApiClient.class);
    private final SpotifyRecentlyPlayedService service = new SpotifyRecentlyPlayedService(client);
    private final Request dummyRequest = Request.create(
            Request.HttpMethod.GET,
            "https://api.spotify.com/v1/me/player/recently-played?before=12345",
            Map.of(),
            null,
            StandardCharsets.UTF_8,
            null
    );

    @Test
    void rejectsInvalidInputBeforeCallingClient() {
        assertThrows(IllegalArgumentException.class, () -> service.fetchPage(null, 20, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchPage("  ", 20, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchPage("tok", 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchPage("tok", 51, null));
        assertThrows(IllegalArgumentException.class, () -> service.fetchPage("tok", 20, 0L));
        verifyNoInteractions(client);
    }

    @Test
    void translates401And403WithoutLeakingTokenOrBody() {
        when(client.getRecentlyPlayed("Bearer secret-token", 50, null))
                .thenThrow(feignError(401, "{\"error\":\"secret-token\"}", Map.of()))
                .thenThrow(feignError(403, "{\"error\":\"allowlist-secret\"}", Map.of()));

        var unauth = assertThrows(SpotifyApiException.class, () -> service.fetchPage("secret-token", 50, null));
        assertEquals(Category.UNAUTHORIZED, unauth.getCategory());
        assertFalse(unauth.getMessage().contains("secret-token"));

        var forbidden = assertThrows(SpotifyApiException.class, () -> service.fetchPage("secret-token", 50, null));
        assertEquals(Category.FORBIDDEN, forbidden.getCategory());
        assertFalse(forbidden.getMessage().contains("allowlist-secret"));
    }

    @Test
    void translates429AndPreservesProviderRetryAfter() {
        when(client.getRecentlyPlayed("Bearer tok", 50, null))
                .thenThrow(feignError(429, "{}", Map.of("Retry-After", List.of("45"))))
                .thenThrow(feignError(429, "{}", Map.of("Retry-After", List.of("900"))))
                .thenThrow(feignError(429, "{}", Map.of("Retry-After", List.of("invalid"))));

        var first = assertThrows(SpotifyApiException.class, () -> service.fetchPage("tok", 50, null));
        assertEquals(Category.RATE_LIMITED, first.getCategory());
        assertEquals(45L, first.getRetryAfterSeconds());

        var longerDelay = assertThrows(SpotifyApiException.class, () -> service.fetchPage("tok", 50, null));
        assertEquals(900L, longerDelay.getRetryAfterSeconds());

        var invalid = assertThrows(SpotifyApiException.class, () -> service.fetchPage("tok", 50, null));
        assertNull(invalid.getRetryAfterSeconds());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 502, 503})
    void translatesOtherHttpErrorsToUnavailable(int status) {
        when(client.getRecentlyPlayed("Bearer tok", 20, 100L))
                .thenThrow(feignError(status, "{\"secret\":\"body\"}", Map.of()));

        var ex = assertThrows(SpotifyApiException.class, () -> service.fetchPage("tok", 20, 100L));
        assertEquals(Category.UNAVAILABLE, ex.getCategory());
        assertFalse(ex.getMessage().contains("100"));
        assertFalse(ex.getMessage().contains("secret"));
    }

    @Test
    void translatesSocketTimeoutAndNullResponse() {
        var timeoutEx = new RetryableException(
                -1, "timed out", Request.HttpMethod.GET, new SocketTimeoutException("read timed out"), 0L, dummyRequest);
        when(client.getRecentlyPlayed("Bearer tok", 20, null))
                .thenThrow(timeoutEx)
                .thenReturn(new SpotifyRecentlyPlayedResponseDto(null, null, 20));

        assertEquals(Category.TIMEOUT,
                assertThrows(SpotifyApiException.class, () -> service.fetchPage("tok", 20, null)).getCategory());
        assertEquals(Category.UNAVAILABLE,
                assertThrows(SpotifyApiException.class, () -> service.fetchPage("tok", 20, null)).getCategory());
    }

    private FeignException feignError(int status, String body, Map<String, Collection<String>> headers) {
        return FeignException.errorStatus("getRecentlyPlayed", feign.Response.builder()
                .status(status)
                .reason("error")
                .request(dummyRequest)
                .headers(headers)
                .body(body, StandardCharsets.UTF_8)
                .build());
    }
}

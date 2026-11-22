package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collection;
import java.util.Map;

@Service
public class SpotifyRecentlyPlayedService {

    public static final int MAX_PAGE_LIMIT = 50;

    private final SpotifyApiClient client;

    public SpotifyRecentlyPlayedService(SpotifyApiClient client) {
        this.client = client;
    }

    // Consulta uma página de reproduções recentes com validação de limites e erros sanitizados.
    public SpotifyRecentlyPlayedResponseDto fetchPage(String accessToken, int limit, Long beforeCursorMs) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Spotify access token is required.");
        }
        if (limit < 1 || limit > MAX_PAGE_LIMIT) {
            throw new IllegalArgumentException("Spotify limit must be between 1 and " + MAX_PAGE_LIMIT + ".");
        }
        if (beforeCursorMs != null && beforeCursorMs <= 0L) {
            throw new IllegalArgumentException("Spotify before cursor must be positive.");
        }

        try {
            SpotifyRecentlyPlayedResponseDto response = client.getRecentlyPlayed(
                    "Bearer " + accessToken, limit, beforeCursorMs);
            if (response == null || response.items() == null) {
                throw new SpotifyApiException(Category.UNAVAILABLE);
            }
            return response;
        } catch (SpotifyApiException exception) {
            throw exception;
        } catch (FeignException exception) {
            throw translateFeignException(exception);
        } catch (RuntimeException exception) {
            // Falhas de desserialização ou transporte sem status HTTP não vazam detalhes externos.
            throw new SpotifyApiException(Category.UNAVAILABLE);
        }
    }

    private static SpotifyApiException translateFeignException(FeignException exception) {
        int status = exception.status();
        if (status == 401) {
            return new SpotifyApiException(Category.UNAUTHORIZED);
        }
        if (status == 403) {
            return new SpotifyApiException(Category.FORBIDDEN);
        }
        if (status == 429) {
            return new SpotifyApiException(Category.RATE_LIMITED, parseRetryAfter(exception.responseHeaders()));
        }
        if (status >= 400) {
            return new SpotifyApiException(Category.UNAVAILABLE);
        }
        if (exception instanceof RetryableException) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                    return new SpotifyApiException(Category.TIMEOUT);
                }
            }
        }
        return new SpotifyApiException(Category.UNAVAILABLE);
    }

    private static Long parseRetryAfter(Map<String, Collection<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (var entry : headers.entrySet()) {
            if (!"retry-after".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            for (String raw : entry.getValue()) {
                if (raw == null || raw.isBlank()) continue;
                try {
                    long seconds = Long.parseLong(raw.trim());
                    if (seconds > 0L) {
                        return seconds;
                    }
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}

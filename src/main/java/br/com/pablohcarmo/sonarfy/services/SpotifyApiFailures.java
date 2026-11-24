package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import feign.FeignException;
import feign.RetryableException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collection;
import java.util.Map;

// Tradução comum aos consumidores da API Web, sem preservar detalhes externos.
final class SpotifyApiFailures {
    private SpotifyApiFailures() {}

    static SpotifyApiException translateFeignException(FeignException exception) {
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

package br.com.pablohcarmo.sonarfy.exceptions;

// Traduz falhas da API Web do Spotify sem preservar tokens, cursores ou corpo externo.
public class SpotifyApiException extends RuntimeException {

    public enum Category { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, UNAVAILABLE, TIMEOUT }

    private final Category category;
    private final Long retryAfterSeconds;

    public SpotifyApiException(Category category) {
        this(category, null);
    }

    public SpotifyApiException(Category category, Long retryAfterSeconds) {
        super(switch (category) {
            case UNAUTHORIZED -> "Spotify rejected the access token.";
            case FORBIDDEN -> "Spotify denied access to the requested resource.";
            case NOT_FOUND -> "Spotify album was not found.";
            case RATE_LIMITED -> "Spotify rate limit exceeded.";
            case UNAVAILABLE -> "Spotify Web API is currently unavailable.";
            case TIMEOUT -> "Spotify Web API request timed out.";
        });
        this.category = category;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Category getCategory() {
        return category;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

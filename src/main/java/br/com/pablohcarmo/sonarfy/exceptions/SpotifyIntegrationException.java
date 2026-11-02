package br.com.pablohcarmo.sonarfy.exceptions;

public class SpotifyIntegrationException extends RuntimeException {

    public enum Category { REJECTED, UNAVAILABLE, TIMEOUT }

    private final Category category;

    public SpotifyIntegrationException(Category category) {
        // Não preserva a exceção externa, que pode conter credenciais ou tokens.
        super(switch (category) {
            case REJECTED -> "Spotify rejected the token exchange request.";
            case UNAVAILABLE -> "Spotify token exchange is currently unavailable.";
            case TIMEOUT -> "Spotify token exchange timed out.";
        });
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}

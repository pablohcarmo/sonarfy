package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class SpotifyTokenResponseValidator {
    private SpotifyTokenResponseValidator() {}

    static void validate(SpotifyTokenResponseDto response) {
        validate(response, true);
    }

    static void validateRefresh(SpotifyTokenResponseDto response) {
        validate(response, false);
    }

    private static void validate(SpotifyTokenResponseDto response, boolean initial) {
        if (response == null
                || response.accessToken() == null || response.accessToken().isBlank()
                || (initial && response.refreshToken() == null)
                || (response.refreshToken() != null && response.refreshToken().isBlank())
                || !"Bearer".equalsIgnoreCase(response.tokenType())
                || response.expiresIn() == null
                || response.expiresIn() < 1L || response.expiresIn() > 3600L) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Spotify returned an invalid token response.");
        }
    }
}

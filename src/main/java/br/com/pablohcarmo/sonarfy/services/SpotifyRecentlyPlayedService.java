package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import feign.FeignException;
import org.springframework.stereotype.Service;


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
            throw SpotifyApiFailures.translateFeignException(exception);
        } catch (RuntimeException exception) {
            // Falhas de desserialização ou transporte sem status HTTP não vazam detalhes externos.
            throw new SpotifyApiException(Category.UNAVAILABLE);
        }
    }

}

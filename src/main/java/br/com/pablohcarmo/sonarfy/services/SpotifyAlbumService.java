package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.dto.SpotifyAlbumResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import feign.FeignException;
import org.springframework.stereotype.Service;

@Service
public class SpotifyAlbumService {
    private final SpotifyApiClient client;

    public SpotifyAlbumService(SpotifyApiClient client) { this.client = client; }

    // Recebe credencial do chamador; não renova tokens nem grava catálogo nesta etapa.
    public SpotifyAlbumResponseDto fetch(String accessToken, String spotifyId, String market) {
        if (accessToken == null || accessToken.isBlank())
            throw new IllegalArgumentException("Spotify access token is required.");
        if (spotifyId == null || !spotifyId.matches("[A-Za-z0-9]{22}"))
            throw new IllegalArgumentException("Invalid Spotify album ID.");
        if (market != null && !market.matches("[A-Z]{2}"))
            throw new IllegalArgumentException("Market must use two uppercase letters.");
        try {
            var album = client.getAlbum("Bearer " + accessToken, spotifyId, market);
            if (album == null || !spotifyId.equals(album.id()) || !"album".equals(album.type())
                    || album.name() == null || album.name().isBlank()
                    || album.totalTracks() == null || album.totalTracks() < 0
                    || album.artists() == null || album.artists().isEmpty()
                    || album.artists().stream().anyMatch(a -> a == null || a.id() == null || a.id().isBlank()
                            || a.name() == null || a.name().isBlank()))
                throw new SpotifyApiException(Category.UNAVAILABLE);
            return album;
        } catch (FeignException exception) {
            if (exception.status() == 404) throw new SpotifyApiException(Category.NOT_FOUND);
            throw SpotifyApiFailures.translateFeignException(exception);
        } catch (SpotifyApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SpotifyApiException(Category.UNAVAILABLE);
        }
    }
}

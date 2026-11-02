package br.com.pablohcarmo.sonarfy.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Service
public class SpotifyAuthorizationService {

    // Delega a geração e o armazenamento do state ao service dedicado.
    private final SpotifyAuthorizationStateService stateService;
    private final String clientId;
    private final String redirectUri;

    public SpotifyAuthorizationService(
            SpotifyAuthorizationStateService stateService,
            @Value("${SPOTIFY_CLIENT_ID}") String clientId,
            @Value("${SPOTIFY_REDIRECT_URI}") String redirectUri
    ) {
        this.stateService = stateService;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    public URI startAuthorization(Long userId) {
        // Cria uma tentativa vinculada ao usuário autenticado.
        String state = stateService.create(userId);

        // Monta o endereço de consentimento; não faz chamada HTTP ao Spotify.
        return UriComponentsBuilder
                .fromUriString("https://accounts.spotify.com/authorize")
                .queryParam("response_type", "code") // Solicita um código de autorização.
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("scope", "{scope}") // Solicita leitura da biblioteca.
                .queryParam("state", "{state}")
                .encode() // Protege caracteres especiais nos valores dos parâmetros.
                .buildAndExpand(Map.of(
                        "clientId", clientId,
                        "redirectUri", redirectUri,
                        "scope", "user-library-read",
                        "state", state
                ))
                .toUri(); // Retorna a URL que o navegador deverá abrir.
    }
}

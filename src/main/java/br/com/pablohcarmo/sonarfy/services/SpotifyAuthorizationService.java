package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Service
public class SpotifyAuthorizationService {

    // Delega a geração e o armazenamento do state ao service dedicado.
    private final SpotifyAuthorizationStateService stateService;
    private final UserRepository users;
    private final String clientId;
    private final String redirectUri;

    public SpotifyAuthorizationService(
            SpotifyAuthorizationStateService stateService,
            UserRepository users,
            @Value("${SPOTIFY_CLIENT_ID}") String clientId,
            @Value("${SPOTIFY_REDIRECT_URI}") String redirectUri
    ) {
        this.stateService = stateService;
        this.users = users;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    @Transactional(readOnly = true)
    public URI startAuthorization(Long userId) {
        var user = users.findById(userId)
                .filter(candidate -> candidate.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "User cannot start Spotify authorization."
                ));
        // Cria uma tentativa vinculada ao usuário autenticado.
        String state = stateService.create(userId, user.getSpotifyAuthorizationVersion());

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

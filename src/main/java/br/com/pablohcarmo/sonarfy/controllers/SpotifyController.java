package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.SpotifyAuthorizationResponseDto;
import br.com.pablohcarmo.sonarfy.dto.SpotifyConnectionResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.SpotifyAuthorizationService;
import br.com.pablohcarmo.sonarfy.services.SpotifyCallbackService;
import br.com.pablohcarmo.sonarfy.services.SpotifyConnectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;

@RestController
@RequestMapping("/api/spotify")
public class SpotifyController {

    private final UserRepository userRepository;
    private final SpotifyAuthorizationService spotifyAuthorizationService;
    private final SpotifyCallbackService spotifyCallbackService;
    private final SpotifyConnectionService spotifyConnectionService;
    private final URI frontendRedirectUri;

    public SpotifyController(
            UserRepository userRepository,
            SpotifyAuthorizationService spotifyAuthorizationService,
            SpotifyCallbackService spotifyCallbackService,
            SpotifyConnectionService spotifyConnectionService,
            @Value("${SPOTIFY_FRONTEND_REDIRECT_URI}") String frontendRedirectUri
    ) {
        this.userRepository = userRepository;
        this.spotifyAuthorizationService = spotifyAuthorizationService;
        this.spotifyCallbackService = spotifyCallbackService;
        this.spotifyConnectionService = spotifyConnectionService;
        this.frontendRedirectUri = validateFrontendRedirectUri(frontendRedirectUri);
    }

    @PostMapping("/authorization")
    public ResponseEntity<SpotifyAuthorizationResponseDto> startAuthorization(
            Principal principal // Recebe o e-mail do usuário autenticado.
    ) {
        User user = userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ));

        // Registra um state vinculado ao ID do usuário e monta a URL de consentimento.
        URI authorizationUrl = spotifyAuthorizationService.startAuthorization(user.getId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // Não armazena a resposta em cache.
                .body(new SpotifyAuthorizationResponseDto(
                        authorizationUrl.toString()
                ));
    }


    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error
    ) {
        // Delega a validação, o consumo do state e a troca do código, traduzindo o desfecho para o frontend.
        String outcome = spotifyCallbackService.resolveRedirectOutcome(code, state, error);
        URI location = UriComponentsBuilder.fromUri(frontendRedirectUri)
                .replaceQueryParam("spotify", outcome)
                .build(true)
                .toUri();

        // Redireciona o navegador ao frontend sem expor tokens nem armazenar a resposta em cache.
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(location)
                .build();
    }

    // Valida a URI de retorno do frontend na criação do controller para impedir redirecionamento aberto.
    private static URI validateFrontendRedirectUri(String frontendRedirectUri) {
        if (frontendRedirectUri == null || frontendRedirectUri.isBlank()) {
            throw new IllegalArgumentException("SPOTIFY_FRONTEND_REDIRECT_URI is required.");
        }

        final URI uri;
        // Interpreta a sintaxe da URI sem incluir o valor informado na mensagem de erro.
        try {
            uri = new URI(frontendRedirectUri);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("SPOTIFY_FRONTEND_REDIRECT_URI must be a valid URI.");
        }

        String host = uri.getHost();
        String scheme = uri.getScheme();
        // Permite HTTP apenas para endereços locais de desenvolvimento; nos demais, exige HTTPS.
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host);
        boolean allowedScheme = "https".equalsIgnoreCase(scheme)
                || ("http".equalsIgnoreCase(scheme) && loopback);

        // Rejeita caminhos relativos, credenciais embutidas, fragmentos e portas fora da faixa válida.
        if (host == null
                || !allowedScheme
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535
                || uri.getPort() == 0) {
            throw new IllegalArgumentException(
                    "SPOTIFY_FRONTEND_REDIRECT_URI must use HTTPS, or HTTP with a loopback host, "
                            + "without user information or a fragment, and with a valid port."
            );
        }
        return uri;
    }

    @GetMapping("/connection")
    public ResponseEntity<SpotifyConnectionResponseDto> getConnection(
            Principal principal
    ) {
        // Identifica o usuário autenticado, consulta o service e retorna o resultado sem permitir cache
        User user = userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ));

        SpotifyConnectionResponseDto connection = spotifyConnectionService.getConnection(user.getId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(connection);
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> deleteConnection(
            Principal principal
    ) {
        // Identifica o usuário autenticado, consulta o service e retorna o resultado sem permitir cache
        User user = userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ));

        spotifyConnectionService.disconnect(user.getId());

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}

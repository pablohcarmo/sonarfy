package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.SpotifyAuthorizationResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.SpotifyAuthorizationService;
import br.com.pablohcarmo.sonarfy.services.SpotifyCallbackService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;

@RestController
@RequestMapping("/api/spotify")
public class SpotifyController {

    private final UserRepository userRepository;
    private final SpotifyAuthorizationService spotifyAuthorizationService;
    private final SpotifyCallbackService spotifyCallbackService;

    public SpotifyController(UserRepository userRepository, SpotifyAuthorizationService spotifyAuthorizationService, SpotifyCallbackService spotifyCallbackService) {
        this.userRepository = userRepository;
        this.spotifyAuthorizationService = spotifyAuthorizationService;
        this.spotifyCallbackService = spotifyCallbackService;
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
        // Delega a validação, o consumo do state e a troca do código
        spotifyCallbackService.handleCallback(code, state, error);

        // Confirma a conclusão sem expor tokens nem armazenar a resposta em cache.
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}

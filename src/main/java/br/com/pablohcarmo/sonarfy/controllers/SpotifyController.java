package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.SpotifyAuthorizationResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.SpotifyAuthorizationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;

@RestController
@RequestMapping("/api/spotify")
public class SpotifyController {

    private final UserRepository userRepository;
    private final SpotifyAuthorizationService spotifyAuthorizationService;

    public SpotifyController(UserRepository userRepository, SpotifyAuthorizationService spotifyAuthorizationService) {
        this.userRepository = userRepository;
        this.spotifyAuthorizationService = spotifyAuthorizationService;
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
}

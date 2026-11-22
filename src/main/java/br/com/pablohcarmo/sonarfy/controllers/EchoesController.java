package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.EchoSyncResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService.RetryAfterException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/echoes")
public class EchoesController {

    private final EchoSyncService syncService;

    public EchoesController(EchoSyncService syncService) {
        this.syncService = syncService;
    }

    // Sincroniza escutas recentes do Spotify exclusivamente para o usuário autenticado.
    @PostMapping("/sync")
    public ResponseEntity<EchoSyncResponseDto> sync(@AuthenticationPrincipal User authenticatedUser) {
        try {
            var result = syncService.sync(authenticatedUser.getId());
            var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
            if (result.retryAfterSeconds() != null) {
                response.header("Retry-After", Long.toString(result.retryAfterSeconds()));
            }
            return response.body(result.body());
        } catch (RetryAfterException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .cacheControl(CacheControl.noStore())
                    .headers(exception.responseHeaders())
                    .build();
        }
    }
}

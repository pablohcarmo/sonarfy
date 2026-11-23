package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.EchoSyncResponseDto;
import br.com.pablohcarmo.sonarfy.dto.EchoAlbumsPageResponseDto;
import org.springframework.web.bind.annotation.PathVariable;
import br.com.pablohcarmo.sonarfy.dto.EchoesPageResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.EchoQueryService;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService.RetryAfterException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/echoes")
public class EchoesController {

    private final EchoSyncService syncService;
    private final EchoQueryService queryService;

    public EchoesController(EchoSyncService syncService, EchoQueryService queryService) {
        this.syncService = syncService;
        this.queryService = queryService;
    }

    // Lista escutas importadas do usuário autenticado com paginação keyset.
    @GetMapping
    public ResponseEntity<EchoesPageResponseDto> list(
            @AuthenticationPrincipal User authenticatedUser,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor
    ) {
        var page = queryService.list(authenticatedUser.getId(), limit, cursor);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(page);
    }

    // Lista álbuns distintos pela reprodução mais recente do usuário.
    @GetMapping("/albums")
    public ResponseEntity<EchoAlbumsPageResponseDto> albums(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queryService.albums(user.getId(), limit, cursor));
    }

    // Expande um álbum sem limitar seu histórico ? página de álbuns carregada.
    @GetMapping("/albums/{spotifyId}/listens")
    public ResponseEntity<EchoesPageResponseDto> albumHistory(
            @AuthenticationPrincipal User user, @PathVariable String spotifyId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queryService.albumHistory(user.getId(), spotifyId, limit, cursor));
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

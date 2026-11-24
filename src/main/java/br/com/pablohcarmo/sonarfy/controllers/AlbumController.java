package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.AlbumDetailsDto;
import br.com.pablohcarmo.sonarfy.dto.AlbumSearchPageDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.exceptions.*;
import br.com.pablohcarmo.sonarfy.services.AlbumDetailsService;
import br.com.pablohcarmo.sonarfy.services.AlbumSearchService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/albums")
public class AlbumController {
    private final AlbumDetailsService albums;
    private final AlbumSearchService search;
    public AlbumController(AlbumDetailsService albums, AlbumSearchService search) {
        this.albums = albums; this.search = search;
    }

    // Busca independente de OAuth pessoal e das rotas existentes de detalhes e avaliacoes.
    @GetMapping("/search")
    public ResponseEntity<AlbumSearchPageDto> search(
            @AuthenticationPrincipal User actor, @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit, @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(actor.getId(), q, limit, offset));
    }

    // Identidade do solicitante vem do Bearer Sonarfy, sem exigir conexão Spotify pessoal.
    @GetMapping("/{spotifyId}")
    public ResponseEntity<AlbumDetailsDto> get(@AuthenticationPrincipal User actor, @PathVariable String spotifyId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(albums.get(actor.getId(), spotifyId));
    }

    // Erros externos usam respostas vazias e não expõem corpo, token ou detalhes do provedor.
    @ExceptionHandler(SpotifyApiException.class)
    ResponseEntity<Void> apiFailure(SpotifyApiException e) {
        var status = switch (e.getCategory()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED, FORBIDDEN -> HttpStatus.BAD_GATEWAY;
            case RATE_LIMITED, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
        };
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (e.getRetryAfterSeconds() != null) response.header("Retry-After", e.getRetryAfterSeconds().toString());
        return response.build();
    }

    @ExceptionHandler(SpotifyIntegrationException.class)
    ResponseEntity<Void> tokenFailure(SpotifyIntegrationException e) {
        return ResponseEntity.status(switch (e.getCategory()) {
            case REJECTED -> HttpStatus.BAD_GATEWAY;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
        }).cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> invalid(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).build();
    }
}

package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api")
public class ReviewController {
    private final ReviewService reviews;
    public ReviewController(ReviewService reviews) { this.reviews = reviews; }

    // O autor vem somente do Bearer, inclusive quando há IDs extras no corpo.
    @PostMapping("/albums/{spotifyId}/reviews")
    public ResponseEntity<ReviewDto> create(@AuthenticationPrincipal User actor, @PathVariable String spotifyId,
                                           @RequestBody ReviewInputDto input) {
        var result = reviews.create(actor.getId(), spotifyId, input);
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/albums/{spotifyId}/reviews/me")
                        .buildAndExpand(spotifyId).encode().toUri())
                .header("Cache-Control", "no-store").body(result);
    }

    @GetMapping("/albums/{spotifyId}/reviews/me")
    public ResponseEntity<ReviewDto> current(@AuthenticationPrincipal User actor, @PathVariable String spotifyId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reviews.current(actor.getId(), spotifyId));
    }

    @PutMapping("/reviews/{reviewId}")
    public ResponseEntity<ReviewDto> replace(@AuthenticationPrincipal User actor, @PathVariable long reviewId,
                                            @RequestBody ReviewInputDto input) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reviews.replace(actor.getId(), reviewId, input));
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User actor, @PathVariable long reviewId) {
        reviews.delete(actor.getId(), reviewId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reviews/me")
    public ResponseEntity<ReviewDto.Page> list(@AuthenticationPrincipal User actor,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) Long beforeId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reviews.list(actor.getId(), limit, beforeId));
    }
}

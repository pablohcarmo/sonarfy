package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.FollowingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile/{targetId}/following")
public class FollowingController {
    private final FollowingService following;

    public FollowingController(FollowingService following) { this.following = following; }

    // A identidade do seguidor vem exclusivamente da autenticação.
    @PutMapping
    public ResponseEntity<Void> follow(@PathVariable long targetId, @AuthenticationPrincipal User actor) {
        following.change(actor.getId(), targetId, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unfollow(@PathVariable long targetId, @AuthenticationPrincipal User actor) {
        following.change(actor.getId(), targetId, false);
        return ResponseEntity.noContent().build();
    }
}

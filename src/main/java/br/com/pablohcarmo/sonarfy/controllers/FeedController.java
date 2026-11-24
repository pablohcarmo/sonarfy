package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.FeedDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.FeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/feed")
public class FeedController {
    private final FeedService feed;
    public FeedController(FeedService feed) { this.feed = feed; }

    // O visitante é definido exclusivamente pelo Bearer validado pela segurança.
    @GetMapping
    public ResponseEntity<FeedDto> list(@AuthenticationPrincipal User actor,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(feed.list(actor.getId(), limit, cursor));
    }
}

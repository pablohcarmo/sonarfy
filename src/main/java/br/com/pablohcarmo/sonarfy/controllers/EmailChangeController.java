package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.EmailChangeRequestService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "app.email-change.enabled", havingValue = "true")
public class EmailChangeController {
    private final EmailChangeRequestService service;
    public EmailChangeController(EmailChangeRequestService service) { this.service = service; }

    @PostMapping("/api/users/me/email-change-request")
    public ResponseEntity<EmailChangeAcceptedDto> request(@AuthenticationPrincipal User user,
                                                        @Valid @RequestBody RequestEmailChangeDto dto) {
        service.request(user.getId(), dto);
        return ResponseEntity.accepted().body(new EmailChangeAcceptedDto(
                "Email change request accepted for delivery.", EmailChangeRequestService.TTL_SECONDS));
    }
}

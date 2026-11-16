package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.services.EmailChangeConfirmationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "app.email-change.enabled", havingValue = "true")
public class EmailChangeConfirmationController {
    private final EmailChangeConfirmationService service;
    public EmailChangeConfirmationController(EmailChangeConfirmationService service) { this.service = service; }

    @PostMapping("/api/auth/email-change-confirmation")
    public ResponseEntity<EmailChangeConfirmedDto> confirm(@Valid @RequestBody ConfirmEmailChangeDto dto) {
        service.confirm(dto.token());
        return ResponseEntity.ok(new EmailChangeConfirmedDto("Email changed successfully. Please log in again.", true));
    }
}

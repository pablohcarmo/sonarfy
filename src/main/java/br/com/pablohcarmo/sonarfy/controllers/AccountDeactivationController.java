package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestController
@ConditionalOnProperty(name = "app.account-deactivation.enabled", havingValue = "true")
public class AccountDeactivationController {
    private final AccountDeactivationRequestService requests;
    private final AccountDeactivationConfirmationService confirmations;
    public AccountDeactivationController(AccountDeactivationRequestService requests, AccountDeactivationConfirmationService confirmations) {
        this.requests = requests; this.confirmations = confirmations;
    }
    public record Request(@NotBlank String currentPassword) {
        @Override public String toString() { return "Request[currentPassword=hidden]"; }
    }
    public record Confirmation(@NotBlank @Size(max = 256) String token) {
        @Override public String toString() { return "Confirmation[token=hidden]"; }
    }
    public record Accepted(String message, long expiresInSeconds) {}

    // A identidade vem exclusivamente do principal; o serviço não retorna o segredo.
    @PostMapping(value = "/api/users/me/account-deactivation-request", consumes = "application/json")
    public ResponseEntity<Accepted> request(@AuthenticationPrincipal User user, @Valid @RequestBody Request input) {
        requests.request(user.getId(), input.currentPassword());
        return ResponseEntity.accepted().body(new Accepted("Account deactivation request accepted for delivery.", 900));
    }

    // O token no corpo é a única autorização; não há confirmação por GET ou emissão de sessão.
    @PostMapping(value = "/api/auth/account-deactivation-confirmation", consumes = "application/json")
    public ResponseEntity<Void> confirm(@Valid @RequestBody Confirmation input) {
        confirmations.confirm(input.token());
        return ResponseEntity.noContent().build();
    }

    // Evita que o tratamento padrão registre valores rejeitados de senha ou token.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalidInput() { return ResponseEntity.badRequest().build(); }
}

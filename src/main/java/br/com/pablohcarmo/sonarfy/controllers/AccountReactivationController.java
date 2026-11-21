package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.services.AccountReactivationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountReactivationController {
    private final AccountReactivationService service;

    public AccountReactivationController(AccountReactivationService service) { this.service = service; }

    // As credenciais identificam a conta; nenhum identificador de ator é aceito como autorização.
    @PostMapping("/api/auth/reactivation")
    public ResponseEntity<Void> reactivate(@Valid @RequestBody LoginDto credentials) {
        service.reactivate(credentials);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}

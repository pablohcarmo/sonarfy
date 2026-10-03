package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/auth")
public class AuthController {
    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@Valid @RequestBody NewUserDto newUserDto) {
        userService.newUser(newUserDto);
        return ResponseEntity.ok("User registered successfully");
    }

    @PostMapping("/resend-activation-email")
    public ResponseEntity<String> resendActivationEmail(@RequestParam("email") String email) {
        String result = userService.resendActivationEmail(email);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyEmail(@RequestParam("token") String token) {
        String result = userService.verifyToken(token);

        if(result.contains("success") || result.contains("already verified")) {
            return ResponseEntity.ok(result);
        } else {
            // Retorna 400 Bad Request apenas se o o token for inválido, corrompido ou expirado
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/password-reset")
    public ResponseEntity<String> requestPasswordReset(@RequestParam("email") String email) {
        String result = userService.sendPasswordChangeRequest(email);
        return ResponseEntity.ok(result);
    }
}
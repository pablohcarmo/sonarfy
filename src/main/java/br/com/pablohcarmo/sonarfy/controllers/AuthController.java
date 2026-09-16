package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.services.UserService;
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
    public ResponseEntity<?> registerUser(@RequestBody NewUserDto newUserDto) {
        userService.newUser(newUserDto);
        return ResponseEntity.ok("User registered successfully");
    }

    @PostMapping("/verify")
    public ResponseEntity<String> verifyEmail(@RequestParam("token") String token) {
        String result = userService.verifyToken(token);

        if(result.contains("success")) {
            return ResponseEntity.ok(result);
        } else {
            // Retorna 400 Bad Request com a mensagem de erro se o token for inválido ou expirado
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/password-reset")
    public ResponseEntity<String> requestPasswordReset(@RequestParam("email") String email) {
        String result = userService.sendPasswordResetEmail();
        return ResponseEntity.ok(result);
    }

}
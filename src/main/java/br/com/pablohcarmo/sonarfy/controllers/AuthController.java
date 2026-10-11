package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.dto.RegisterResponseDto;
import br.com.pablohcarmo.sonarfy.dto.UpdatePendingEmailDto;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("api/auth")
public class AuthController {
    private final UserService userService;
    private final JwtService jwtService;
    private final AuthEmailService authEmailService;

    public AuthController(UserService userService, JwtService jwtService, AuthEmailService authEmailService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.authEmailService = authEmailService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDto> registerUser(@Valid @RequestBody NewUserDto newUserDto) {
        RegisterResponseDto response = userService.newUser(newUserDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/resend-activation-email")
    public ResponseEntity<String> resendActivationEmail(@RequestParam("email") String email) {
        String result = authEmailService.resendActivationEmail(email);
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

    @PatchMapping("/update-pending-email")
    public ResponseEntity<String> updatePendingEmail(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody UpdatePendingEmailDto dto
    ) {
        if( authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization token.");
        }

        String token = authHeader.substring(7).trim();
        String userIdStr = jwtService.validateRegistrationTokenAndGetUserId(token);

        if(userIdStr == null || userIdStr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired registration token.");
        }

        long userId;
        try {
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e){
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user ID in token.");
        }

        String result = userService.updatePendingEmail(userId, dto.getNewEmail());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/password-reset")
    public ResponseEntity<String> requestPasswordReset(@RequestParam("email") String email) {
        String result = authEmailService.sendPasswordChangeRequest(email);
        return ResponseEntity.ok(result);
    }
}
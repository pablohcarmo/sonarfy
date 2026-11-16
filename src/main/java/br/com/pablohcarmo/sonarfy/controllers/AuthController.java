package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
import br.com.pablohcarmo.sonarfy.services.AuthService;
import br.com.pablohcarmo.sonarfy.services.RefreshTokenService;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.services.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
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
    private final AuthService authService;
    private final RefreshTokenService refreshTokens;
    @Value("${app.auth.refresh-cookie-secure:${AUTH_REFRESH_COOKIE_SECURE:true}}")
    private boolean secureCookie = true;

    public AuthController(UserService userService, JwtService jwtService, AuthEmailService authEmailService,
                          AuthService authService, RefreshTokenService refreshTokens) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.authEmailService = authEmailService;
        this.authService = authService;
        this.refreshTokens = refreshTokens;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDto> registerUser(@Valid @RequestBody NewUserDto newUserDto) {
        RegisterResponseDto response = userService.newUser(newUserDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@Valid @RequestBody LoginDto dto) {
        return tokenResponse(authService.login(dto));
    }

    // A rotação não participa de transação externa: o serviço confirma revogações antes do 401.
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDto> refresh(
            @CookieValue(name = "sonarfy_refresh", required = false) String token) {
        try {
            return tokenResponse(refreshTokens.rotate(token));
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.UNAUTHORIZED) throw exception;
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, refreshCookie("", false, true)).build();
        }
    }

    // O refresh fica apenas no cookie; o JSON mantém o contrato de access token.
    private ResponseEntity<LoginResponseDto> tokenResponse(RotationDto session) {
        var response = new LoginResponseDto(jwtService.generateAccessToken(session.userId().toString()),
                "Bearer", JwtService.ACCESS_TOKEN_TTL_SECONDS);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken(), session.rememberMe(), false))
                .body(response);
    }

    // A exclusão usa os mesmos atributos do cookie emitido; sessão não define Max-Age.
    private String refreshCookie(String token, boolean rememberMe, boolean clear) {
        var cookie = ResponseCookie.from("sonarfy_refresh", token).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/api/auth");
        if (clear) cookie.maxAge(0);
        else if (rememberMe) cookie.maxAge(RefreshTokenService.INACTIVITY_TTL_SECONDS);
        return cookie.build().toString();
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
            // Retorna 400 Bad Request apenas se o token for inválido, corrompido ou expirado
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

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@Valid @RequestBody ForgotPasswordDto dto) {
        userService.requestPasswordReset(dto.email());
        // Resposta sempre genérica para não revelar se o e-mail existe ou não
        return ResponseEntity.ok(
                "If an eligible account exists, you may receive password reset instructions. Please check your inbox."
        );
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@Valid @RequestBody ResetPasswordDto resetPasswordDto) {
        userService.resetPassword(resetPasswordDto);
        return ResponseEntity.ok("Password reset successfully. You can now log in.");
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthEmailServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthEmailService authEmailService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authEmailService, "baseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(authEmailService, "frontendUrl", "http://localhost:3000");
        try {
            ReflectionTestUtils.setField(authEmailService, "emailCooldownSeconds", 30L);
        } catch (Exception ignored) {
            // Suporta campo caso esteja estático ou de instância
        }

        sampleUser = new User();
        sampleUser.setId(1L);
        sampleUser.setName("Pablo");
        sampleUser.setSurname("Carmo");
        sampleUser.setHandle("pablo");
        sampleUser.setEmail("pablo@sonarfy.com");
        sampleUser.setVerified(false);
        sampleUser.setLastEmailSentAt(null);
    }

    // ==========================================
    // 1. sendActivationEmail
    // ==========================================

    @Test
    @DisplayName("Deve enviar e-mail de ativação, atualizar lastEmailSentAt e salvar usuário com sucesso")
    void shouldSendActivationEmailSuccessfully() {
        String token = "jwt-activation-token-123";

        authEmailService.sendActivationEmail(sampleUser, token);

        verify(emailService, times(1)).sendEmail(
                eq("pablo@sonarfy.com"),
                eq("Ative sua conta no Sonarfy!"),
                argThat(body -> body.contains("http://localhost:3000/verify.html?token=" + token)
                        && body.contains("Pablo Carmo"))
        );
        assertNotNull(sampleUser.getLastEmailSentAt());
        verify(userRepository, times(1)).save(sampleUser);
    }

    @Test
    @DisplayName("Deve enviar e-mail de ativação quando cooldown de 30 segundos já tiver expirado")
    void shouldSendActivationEmailWhenCooldownHasExpired() {
        sampleUser.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(35));

        authEmailService.sendActivationEmail(sampleUser, "token-expirado");

        verify(emailService, times(1)).sendEmail(eq("pablo@sonarfy.com"), any(), any());
        verify(userRepository, times(1)).save(sampleUser);
    }

    @Test
    @DisplayName("Deve lançar 429 Too Many Requests ao tentar enviar e-mail de ativação durante cooldown ativo")
    void shouldThrowTooManyRequestsWhenSendingActivationEmailDuringCooldown() {
        sampleUser.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(10));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                authEmailService.sendActivationEmail(sampleUser, "token-cooldown")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertTrue(ex.getReason().contains("seconds before requesting another email"));
        verify(emailService, never()).sendEmail(any(), any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar RuntimeException quando falhar o transporte do e-mail de ativação")
    void shouldThrowRuntimeExceptionWhenEmailTransportFailsOnActivation() {
        doThrow(new RuntimeException("SMTP offline"))
                .when(emailService).sendEmail(any(), any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                authEmailService.sendActivationEmail(sampleUser, "token-fail")
        );

        assertTrue(ex.getMessage().contains("Failed to send activation token email"));
    }

    // ==========================================
    // 2. resendActivationEmail
    // ==========================================

    @Test
    @DisplayName("Deve reenviar e-mail de ativação com novo token quando usuário for válido e não verificado")
    void shouldResendActivationEmailSuccessfully() {
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(sampleUser));
        when(jwtService.generateEmailConfirmationToken("1")).thenReturn("new-confirmation-token");

        String result = authEmailService.resendActivationEmail("pablo@sonarfy.com");

        assertEquals("Confirmation email resent successfully! Please check your inbox.", result);
        verify(jwtService, times(1)).generateEmailConfirmationToken("1");
        verify(emailService, times(1)).sendEmail(eq("pablo@sonarfy.com"), any(), any());
        verify(userRepository, times(1)).save(sampleUser);
    }

    @Test
    @DisplayName("Deve retornar mensagem de erro ao solicitar reenvio com e-mail nulo, vazio ou em branco")
    void shouldReturnErrorMessageWhenResendingWithInvalidEmail() {
        assertEquals("Invalid e-mail provided for resending confirmation.",
                authEmailService.resendActivationEmail(null));

        assertEquals("Invalid e-mail provided for resending confirmation.",
                authEmailService.resendActivationEmail(""));

        assertEquals("Invalid e-mail provided for resending confirmation.",
                authEmailService.resendActivationEmail("   "));

        verifyNoInteractions(userRepository);
        verifyNoInteractions(jwtService);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Deve lançar UsernameNotFoundException quando usuário não for encontrado no reenvio")
    void shouldThrowUsernameNotFoundExceptionWhenUserNotFoundOnResend() {
        when(userRepository.findByEmailIgnoreCase("inexistente@sonarfy.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () ->
                authEmailService.resendActivationEmail("inexistente@sonarfy.com")
        );

        verify(jwtService, never()).generateEmailConfirmationToken(any());
        verify(emailService, never()).sendEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Deve retornar mensagem informando que conta já foi verificada e não reenviar e-mail")
    void shouldReturnAlreadyVerifiedMessageWhenUserIsAlreadyVerified() {
        sampleUser.setVerified(true);
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(sampleUser));

        String result = authEmailService.resendActivationEmail("pablo@sonarfy.com");

        assertEquals("User already verified. You can log in.", result);
        verify(jwtService, never()).generateEmailConfirmationToken(any());
        verify(emailService, never()).sendEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar 429 Too Many Requests ao solicitar reenvio durante cooldown ativo")
    void shouldThrowTooManyRequestsWhenResendingDuringCooldown() {
        sampleUser.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(15));
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(sampleUser));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                authEmailService.resendActivationEmail("pablo@sonarfy.com")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(emailService, never()).sendEmail(any(), any(), any());
    }

    // ==========================================
    // 3. sendWelcomeEmail
    // ==========================================

    @Test
    @DisplayName("Deve enviar e-mail de boas-vindas com sucesso após ativação da conta")
    void shouldSendWelcomeEmailSuccessfully() {
        authEmailService.sendWelcomeEmail(sampleUser);

        verify(emailService, times(1)).sendEmail(
                eq("pablo@sonarfy.com"),
                eq("Bem-vindo ao Sonarfy!"),
                argThat(body -> body.contains("Pablo Carmo") && body.contains("Sua conta foi ativada com sucesso!"))
        );
    }

    @Test
    @DisplayName("Não deve lançar exceção se envio do e-mail de boas-vindas falhar (tratamento silencioso)")
    void shouldNotThrowExceptionWhenWelcomeEmailFails() {
        doThrow(new RuntimeException("Mail server down"))
                .when(emailService).sendEmail(any(), any(), any());

        assertDoesNotThrow(() -> authEmailService.sendWelcomeEmail(sampleUser));
    }

    // ==========================================
    // 4. sendPasswordChangeRequest
    // ==========================================

    @Test
    @DisplayName("Deve buscar usuário e enviar e-mail de solicitação de redefinição de senha com sucesso")
    void shouldSendPasswordChangeRequestSuccessfully() {
        sampleUser.setVerified(true);
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(sampleUser));

        String result = authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com");

        assertEquals("Reset password email sent successfully! Please check your inbox.", result);
        verify(emailService, times(1)).sendEmail(
                eq("pablo@sonarfy.com"),
                eq("Redefinição de senha - Sonarfy"),
                argThat(body -> body.contains("http://localhost:8080/reset-password")
                        && body.contains("Pablo Carmo"))
        );
        verify(userRepository, times(1)).save(sampleUser);
    }

    @Test
    @DisplayName("Deve retornar mensagem de erro ao solicitar redefinição de senha com e-mail nulo, vazio ou em branco")
    void shouldReturnErrorMessageWhenPasswordResetEmailIsInvalid() {
        assertEquals("Invalid e-mail provided for password reset.",
                authEmailService.sendPasswordChangeRequest(null));
        assertEquals("Invalid e-mail provided for password reset.",
                authEmailService.sendPasswordChangeRequest(""));
        assertEquals("Invalid e-mail provided for password reset.",
                authEmailService.sendPasswordChangeRequest("   "));

        verifyNoInteractions(userRepository);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Deve retornar mensagem de erro quando usuário não for verificado ao solicitar redefinição de senha")
    void shouldReturnErrorMessageWhenUserIsNotVerifiedOnPasswordReset() {
        sampleUser.setVerified(false);
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(sampleUser));

        String result = authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com");

        assertEquals("User is not verified. Please verify your account before requesting a password reset.", result);
        verify(emailService, never()).sendEmail(any(), any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 429 Too Many Requests quando cooldown estiver ativo na solicitação de redefinição de senha")
    void shouldThrowTooManyRequestsWhenCooldownIsActiveOnPasswordReset() {
        sampleUser.setVerified(true);
        sampleUser.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(10));
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(sampleUser));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(emailService, never()).sendEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar UsernameNotFoundException ao solicitar redefinição de senha para e-mail inexistente")
    void shouldThrowUsernameNotFoundExceptionWhenEmailDoesNotExistForPasswordReset() {
        when(userRepository.findByEmailIgnoreCase("naoexiste@sonarfy.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () ->
                authEmailService.sendPasswordChangeRequest("naoexiste@sonarfy.com")
        );

        verify(emailService, never()).sendEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar RuntimeException quando falhar o transporte do e-mail de redefinição de senha")
    void shouldThrowRuntimeExceptionWhenEmailTransportFailsOnPasswordReset() {
        sampleUser.setVerified(true);
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(sampleUser));
        doThrow(new RuntimeException("SMTP Connection refused"))
                .when(emailService).sendEmail(any(), any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com")
        );

        assertTrue(ex.getMessage().contains("Failed to send reset password email"));
    }
}

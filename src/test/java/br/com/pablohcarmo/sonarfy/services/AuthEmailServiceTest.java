package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.exceptions.EmailDeliveryException;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
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

    @Mock
    private EmailCooldownService emailCooldownService;

    @InjectMocks
    private AuthEmailService authEmailService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authEmailService, "frontendUrl", "http://localhost:3000");

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
    @DisplayName("Deve reservar antes de enviar ativação sem salvar entidade antiga")
    void shouldSendActivationEmailSuccessfully() {
        String token = "jwt-activation-token-123";

        authEmailService.sendActivationEmail(sampleUser, token);

        InOrder inOrder = inOrder(emailCooldownService, emailService);
        inOrder.verify(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());
        inOrder.verify(emailService, times(1)).sendEmail(
                eq("pablo@sonarfy.com"),
                eq("Ative sua conta no Sonarfy!"),
                argThat(body -> body.contains("http://localhost:3000/verify.html?token=" + token)
                        && body.contains("Pablo Carmo"))
        );
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve delegar decisão de cooldown à reserva no banco")
    void shouldSendActivationEmailWhenCooldownHasExpired() {
        sampleUser.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(35));

        authEmailService.sendActivationEmail(sampleUser, "token-expirado");

        InOrder inOrder = inOrder(emailCooldownService, emailService);
        inOrder.verify(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());
        inOrder.verify(emailService, times(1)).sendEmail(eq("pablo@sonarfy.com"), any(), any());
    }

    @Test
    @DisplayName("Deve lançar 429 Too Many Requests ao tentar enviar e-mail de ativação durante cooldown ativo")
    void shouldThrowTooManyRequestsWhenSendingActivationEmailDuringCooldown() {
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Cooldown active"))
                .when(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                authEmailService.sendActivationEmail(sampleUser, "token-cooldown")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals("Cooldown active", ex.getReason());
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

        assertEquals("SMTP offline", ex.getMessage());

        InOrder inOrder = inOrder(emailCooldownService, emailService);
        inOrder.verify(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());
        inOrder.verify(emailService).sendEmail(any(), any(), any());
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

        assertEquals("Confirmation email request processed. Please check your inbox.", result);
        verify(jwtService, times(1)).generateEmailConfirmationToken("1");
        verify(emailService, times(1)).sendEmail(eq("pablo@sonarfy.com"), any(), any());
        verify(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), false);
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
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Cooldown active"))
                .when(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());
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
    // 4. sendPasswordResetEmail
    // ==========================================

    @Test
    @DisplayName("Deve enviar e-mail de redefinição de senha com sucesso")
    void shouldSendPasswordResetEmailSuccessfully() {
        sampleUser.setVerified(true);
        String token = "jwt-reset-token-123";

        String result = authEmailService.sendPasswordResetEmail(sampleUser, token);

        assertEquals("Reset password email sent successfully! Please check your inbox.", result);
        InOrder inOrder = inOrder(emailCooldownService, emailService);
        inOrder.verify(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());
        inOrder.verify(emailService, times(1)).sendEmail(
                eq("pablo@sonarfy.com"),
                eq("Redefinição de senha - Sonarfy"),
                argThat(body -> body.contains("http://localhost:3000/reset-password.html?token=" + token)
                        && body.contains("Pablo Carmo"))
        );
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 403 Forbidden quando usuário não for verificado ao solicitar redefinição de senha")
    void shouldThrowForbiddenWhenUserIsNotVerifiedOnPasswordReset() {
        sampleUser.setVerified(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                authEmailService.sendPasswordResetEmail(sampleUser, "token-123")
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("User is not verified"));
        verify(emailService, never()).sendEmail(any(), any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 429 Too Many Requests quando cooldown estiver ativo na solicitação de redefinição de senha")
    void shouldThrowTooManyRequestsWhenCooldownIsActiveOnPasswordReset() {
        sampleUser.setVerified(true);
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Cooldown active"))
                .when(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                authEmailService.sendPasswordResetEmail(sampleUser, "token-123")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(emailService, never()).sendEmail(any(), any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve preservar a exceção específica e sua causa quando a entrega falha")
    void shouldPropagateEmailDeliveryExceptionOnPasswordReset() {
        sampleUser.setVerified(true);
        MailSendException cause = new MailSendException("SMTP Connection refused");
        EmailDeliveryException failure = new EmailDeliveryException("Failed to deliver email.", cause);
        doThrow(failure)
                .when(emailService).sendEmail(any(), any(), any());

        EmailDeliveryException ex = assertThrows(EmailDeliveryException.class, () ->
                authEmailService.sendPasswordResetEmail(sampleUser, "token-123")
        );

        assertSame(failure, ex);
        assertSame(cause, ex.getCause());
        verify(userRepository, never()).save(any());

        InOrder inOrder = inOrder(emailCooldownService, emailService);
        inOrder.verify(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), sampleUser.isVerified());
        inOrder.verify(emailService).sendEmail(any(), any(), any());
    }
    @Test
    void shouldNotReserveOrSendActivationForVerifiedAccount() {
        sampleUser.setVerified(true);
        authEmailService.sendActivationEmail(sampleUser, "unused");
        verifyNoInteractions(emailCooldownService, emailService, userRepository);
    }

    @Test
    void shouldPreserveDeliveryFailureOnActivation() {
        EmailDeliveryException failure = new EmailDeliveryException("Delivery failed",
                new MailSendException("SMTP offline"));
        doThrow(failure).when(emailService).sendEmail(any(), any(), any());
        assertSame(failure, assertThrows(EmailDeliveryException.class,
                () -> authEmailService.sendActivationEmail(sampleUser, "confirmation")));
        InOrder order = inOrder(emailCooldownService, emailService);
        order.verify(emailCooldownService).reserveOrThrow(1L, sampleUser.getEmail(), false);
        order.verify(emailService).sendEmail(any(), any(), any());
        verify(userRepository, never()).save(any());
    }
}

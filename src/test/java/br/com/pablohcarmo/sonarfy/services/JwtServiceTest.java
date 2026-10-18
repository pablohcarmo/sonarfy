package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    @Test
    void shouldSerializeIssuedAtWithWholeSecondPrecision() {
        java.time.Instant issuedAt = java.time.Instant.now().minusSeconds(60)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(987654321);
        String token = com.auth0.jwt.JWT.create()
                .withIssuer("sonarfy-api").withSubject("42")
                .withClaim("purpose", "password_reset")
                .withIssuedAt(issuedAt).withExpiresAt(java.time.Instant.now().plusSeconds(60))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("unit-test-secret-key-super-secure-12345"));
        assertEquals(issuedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                jwtService.getPasswordResetTokenIssuedAt(token));
    }

    @Test
    void shouldReturnNullForMissingIssuedAtWithoutThrowing() {
        String token = com.auth0.jwt.JWT.create()
                .withIssuer("sonarfy-api").withSubject("42")
                .withClaim("purpose", "password_reset")
                .withExpiresAt(java.time.Instant.now().plusSeconds(60))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("unit-test-secret-key-super-secure-12345"));
        assertEquals("42", jwtService.validatePasswordResetTokenAndGetUserId(token));
        assertNull(jwtService.getPasswordResetTokenIssuedAt(token));
    }

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", "unit-test-secret-key-super-secure-12345");
    }

    @Test
    @DisplayName("Deve gerar e validar token de confirmação de e-mail com sucesso")
    void shouldGenerateAndValidateEmailConfirmationToken() {
        String userId = "42";

        String token = jwtService.generateEmailConfirmationToken(userId);
        assertNotNull(token);
        assertFalse(token.isBlank());

        String extractedUserId = jwtService.validateTokenAndGetEmail(token);
        assertEquals(userId, extractedUserId);
    }

    @Test
    @DisplayName("Deve gerar e validar registrationToken para retificação pendente com sucesso")
    void shouldGenerateAndValidateRegistrationToken() {
        String userId = "99";

        String token = jwtService.generateRegistrationToken(userId);
        assertNotNull(token);
        assertFalse(token.isBlank());

        String extractedUserId = jwtService.validateRegistrationTokenAndGetUserId(token);
        assertEquals(userId, extractedUserId);
    }

    @Test
    @DisplayName("Segurança (Isolamento de Propósito): email_confirmation NÃO pode ser usado como registrationToken")
    void shouldRejectEmailConfirmationTokenWhenValidatingAsRegistrationToken() {
        String confirmationToken = jwtService.generateEmailConfirmationToken("10");

        String userId = jwtService.validateRegistrationTokenAndGetUserId(confirmationToken);
        assertNull(userId, "Token com propósito email_confirmation deve ser rejeitado para retificação pendente");
    }

    @Test
    @DisplayName("Segurança (Isolamento de Propósito): pending_registration NÃO pode ser usado como token de ativação")
    void shouldRejectRegistrationTokenWhenValidatingAsEmailConfirmation() {
        String registrationToken = jwtService.generateRegistrationToken("10");

        String userId = jwtService.validateTokenAndGetEmail(registrationToken);
        assertNull(userId, "Token com propósito pending_registration deve ser rejeitado na ativação de conta");
    }

    @Test
    @DisplayName("Deve retornar null ao validar tokens nulos, vazios ou malformados")
    void shouldReturnNullForInvalidTokens() {
        assertNull(jwtService.validateTokenAndGetEmail(null));
        assertNull(jwtService.validateTokenAndGetEmail(""));
        assertNull(jwtService.validateTokenAndGetEmail("   "));
        assertNull(jwtService.validateTokenAndGetEmail("token-invalido-qualquer"));

        assertNull(jwtService.validateRegistrationTokenAndGetUserId(null));
        assertNull(jwtService.validateRegistrationTokenAndGetUserId(""));
        assertNull(jwtService.validateRegistrationTokenAndGetUserId("   "));
        assertNull(jwtService.validateRegistrationTokenAndGetUserId("token-invalido-qualquer"));
    }

    @Test
    @DisplayName("Deve lançar IllegalArgumentException ao tentar gerar token para userId nulo ou em branco")
    void shouldThrowExceptionWhenUserIdIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateEmailConfirmationToken(null));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateEmailConfirmationToken("  "));

        assertThrows(IllegalArgumentException.class, () -> jwtService.generateRegistrationToken(null));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generateRegistrationToken("  "));

        assertThrows(IllegalArgumentException.class, () -> jwtService.generatePasswordResetToken(null));
        assertThrows(IllegalArgumentException.class, () -> jwtService.generatePasswordResetToken("  "));
    }

    @Test
    @DisplayName("Deve gerar e validar token de reset de senha e extrair issuedAt com sucesso")
    void shouldGenerateAndValidatePasswordResetToken() {
        String userId = "55";

        String token = jwtService.generatePasswordResetToken(userId);
        assertNotNull(token);

        String extractedUserId = jwtService.validatePasswordResetTokenAndGetUserId(token);
        assertEquals(userId, extractedUserId);

        assertNotNull(jwtService.getPasswordResetTokenIssuedAt(token));
    }

    @Test
    @DisplayName("Tokens de troca e recuperação de senha devem ter propósitos isolados")
    void shouldIsolatePasswordChangeAndResetTokens() {
        String resetToken = jwtService.generatePasswordResetToken("55");
        String changeToken = jwtService.generatePasswordChangeToken("55");

        assertEquals("55", jwtService.validatePasswordResetTokenAndGetUserId(resetToken));
        assertEquals("55", jwtService.validatePasswordChangeTokenAndGetUserId(changeToken));
        assertNull(jwtService.validatePasswordResetTokenAndGetUserId(changeToken));
        assertNull(jwtService.validatePasswordChangeTokenAndGetUserId(resetToken));
        assertNotNull(jwtService.getPasswordChangeTokenIssuedAt(changeToken));
        assertNull(jwtService.getPasswordChangeTokenIssuedAt(resetToken));
    }
}

package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

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
    }
}

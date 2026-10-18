package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.dto.RegisterResponseDto;
import br.com.pablohcarmo.sonarfy.dto.ChangePasswordDto;
import br.com.pablohcarmo.sonarfy.dto.ResetPasswordDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.events.ActivationEmailRequested;
import org.springframework.context.ApplicationEventPublisher;
import br.com.pablohcarmo.sonarfy.exceptions.EmailDeliveryException;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void injectPersistenceContext() {
        org.springframework.test.util.ReflectionTestUtils.setField(userService, "entityManager", entityManager);
    }

    @Test
    void shouldRejectNonNumericSubjectBeforeDatabaseAccess() {
        when(jwtService.validatePasswordResetTokenAndGetUserId("bad-subject")).thenReturn("abc");
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> userService.resetPassword(new ResetPasswordDto("bad-subject", "New123!", "New123!")));
        assertEquals("Invalid token format!", failure.getReason());
        verifyNoInteractions(userRepository, entityManager, passwordEncoder);
    }

    @Test
    void shouldRejectDeletedAccountBeforeRefresh() {
        when(jwtService.validatePasswordResetTokenAndGetUserId("deleted")).thenReturn("999");
        when(userRepository.findByIdForPasswordUpdate(999L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> userService.resetPassword(new ResetPasswordDto("deleted", "New123!", "New123!")))
                .getStatusCode());
        verifyNoInteractions(entityManager, passwordEncoder);
    }

    @Mock
    private UserRepository userRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthEmailService authEmailService;

    @Mock
    private JwtService jwtService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("Deve registrar novo usuário, publicar ativação e retornar RegisterResponseDto com registrationToken")
    void shouldRegisterNewUserSuccessfully() {
        NewUserDto dto = new NewUserDto();
        dto.setName("Pablo");
        dto.setSurname("Carmo");
        dto.setHandle("@pablo");
        dto.setEmail("pablo@sonarfy.com");
        dto.setPassword("Secret123!");
        dto.setCity("Brasilia");
        dto.setCountry("Brasil");
        dto.setBirthDate(LocalDate.now().minusYears(25));

        Permission defaultPermission = new Permission();
        defaultPermission.setName("ROLE_USER");

        User savedUser = new User();
        savedUser.setId(10L);
        savedUser.setName("Pablo");
        savedUser.setSurname("Carmo");
        savedUser.setHandle("pablo");
        savedUser.setEmail("pablo@sonarfy.com");

        when(userRepository.findByEmailIgnoreCaseOrHandleIgnoreCase("pablo@sonarfy.com", "pablo"))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode("Secret123!")).thenReturn("encodedPassword");
        when(permissionRepository.findByName("ROLE_USER")).thenReturn(Optional.of(defaultPermission));
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateRegistrationToken("10")).thenReturn("token-retificacao-pendente");

        RegisterResponseDto response = userService.newUser(dto);

        assertNotNull(response);
        assertEquals("pablo@sonarfy.com", response.email());
        assertEquals("token-retificacao-pendente", response.registrationToken());
        assertEquals(JwtService.PENDING_REGISTRATION_TTL_SECONDS, response.expiresInSeconds());

        verify(eventPublisher).publishEvent(new ActivationEmailRequested(10L));
        verifyNoInteractions(authEmailService);
        verify(jwtService, times(1)).generateRegistrationToken("10");
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request ao registrar usuário com menos de 13 anos")
    void shouldThrowBadRequestWhenUserIsUnder13() {
        NewUserDto dto = new NewUserDto();
        dto.setBirthDate(LocalDate.now().minusYears(10));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.newUser(dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Deve lançar 409 Conflict ao registrar usuário com e-mail ou handle duplicado")
    void shouldThrowConflictWhenUserAlreadyExists() {
        NewUserDto dto = new NewUserDto();
        dto.setEmail("existente@sonarfy.com");
        dto.setHandle("existente");
        dto.setBirthDate(LocalDate.now().minusYears(20));

        when(userRepository.findByEmailIgnoreCaseOrHandleIgnoreCase("existente@sonarfy.com", "existente"))
                .thenReturn(Optional.of(new User()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.newUser(dto)
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve atualizar e-mail pendente usando ID seguro do token e disparar novo token com sucesso")
    void shouldUpdatePendingEmailSuccessfully() {
        User user = new User();
        user.setId(1L);
        user.setName("Pablo");
        user.setSurname("Carmo");
        user.setHandle("pablo");
        user.setEmail("errado@gmail.com");
        user.setVerified(false);
        user.setLastEmailSentAt(null);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("correto@gmail.com")).thenReturn(Optional.empty());

        String result = userService.updatePendingEmail(1L, "correto@gmail.com");

        assertEquals("correto@gmail.com", user.getEmail());
        assertEquals("Pending email updated successfully!", result);
        verify(userRepository, atLeastOnce()).save(user);
        verify(eventPublisher).publishEvent(new ActivationEmailRequested(1L));
        verifyNoInteractions(authEmailService, jwtService);
    }

    @Test
    @DisplayName("Deve permitir retificação e envio quando cooldown de 30 segundos já tiver expirado")
    void shouldUpdatePendingEmailWhenCooldownHasExpired() {
        User user = new User();
        user.setId(1L);
        user.setName("Pablo");
        user.setSurname("Carmo");
        user.setEmail("errado@gmail.com");
        user.setVerified(false);
        user.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(35)); // 35 segundos atrás (> 30s)

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("novo@gmail.com")).thenReturn(Optional.empty());

        String result = userService.updatePendingEmail(1L, "novo@gmail.com");

        assertEquals("novo@gmail.com", user.getEmail());
        assertEquals("Pending email updated successfully!", result);
        verify(eventPublisher).publishEvent(new ActivationEmailRequested(1L));
        verifyNoInteractions(authEmailService, jwtService);
    }

    @Test
    @DisplayName("Deve salvar retificação e publicar evento mesmo durante cooldown")
    void shouldUpdatePendingEmailAndPublishEvenDuringCooldown() {
        User user = new User();
        user.setId(1L);
        user.setName("Pablo");
        user.setSurname("Carmo");
        user.setEmail("errado@gmail.com");
        user.setVerified(false);
        user.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(10)); // Apenas 10 segundos atrás (< 30s)

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("novo@gmail.com")).thenReturn(Optional.empty());
        assertEquals("Pending email updated successfully!",
                userService.updatePendingEmail(1L, "novo@gmail.com"));
        assertEquals("novo@gmail.com", user.getEmail());
        verify(userRepository).save(user);
        verify(eventPublisher).publishEvent(new ActivationEmailRequested(1L));
        verifyNoInteractions(authEmailService, jwtService);
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando userId ou novo e-mail forem inválidos ou vazios")
    void shouldThrowBadRequestWhenInputsAreInvalid() {
        // userId nulo
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(null, "email@gmail.com")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatusCode());

        // E-mail nulo
        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(1L, null)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatusCode());

        // E-mail em branco
        ResponseStatusException ex3 = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(1L, "   ")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex3.getStatusCode());

        verify(userRepository, never()).save(any());
        verifyNoInteractions(authEmailService);
    }

    @Test
    @DisplayName("Deve lançar 404 Not Found quando userId não for localizado no repositório")
    void shouldThrowNotFoundWhenUserDoesNotExist() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(99L, "novo@gmail.com")
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(authEmailService);
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando a conta já estiver verificada (isVerified = true)")
    void shouldThrowBadRequestWhenUserAlreadyVerified() {
        User user = new User();
        user.setId(3L);
        user.setEmail("pablo@gmail.com");
        user.setVerified(true);

        when(userRepository.findById(3L)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(3L, "novoteste@gmail.com")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(authEmailService);
    }

    @Test
    @DisplayName("Deve lançar 409 Conflict quando o novo e-mail já estiver cadastrado no sistema")
    void shouldThrowConflictWhenNewEmailAlreadyInUse() {
        User user = new User();
        user.setId(4L);
        user.setEmail("antigo@gmail.com");
        user.setVerified(false);

        User anotherUser = new User();
        anotherUser.setId(5L);
        anotherUser.setEmail("duplicado@gmail.com");

        when(userRepository.findById(4L)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("duplicado@gmail.com")).thenReturn(Optional.of(anotherUser));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(4L, "duplicado@gmail.com")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(authEmailService);
    }

    // =========================================================================
    // changePassword tests
    // =========================================================================

    @Test
    @DisplayName("Deve alterar senha do usuário logado com sucesso")
    void shouldChangePasswordSuccessfully() {
        ChangePasswordDto dto = new ChangePasswordDto("token-jwt", "Old123!", "New123!", "New123!");

        User user = new User();
        user.setId(1L);
        user.setEmail("pablo@sonarfy.com");
        user.setPassword("encodedOldPassword");

        when(jwtService.validatePasswordResetTokenAndGetUserId("token-jwt")).thenReturn("1");
        when(userRepository.findByIdForPasswordUpdate(1L)).thenReturn(Optional.of(user));
        when(jwtService.getPasswordResetTokenIssuedAt("token-jwt")).thenReturn(Instant.now().minusSeconds(5));
        when(passwordEncoder.matches("Old123!", "encodedOldPassword")).thenReturn(true);
        when(passwordEncoder.matches("New123!", "encodedOldPassword")).thenReturn(false);
        when(passwordEncoder.encode("New123!")).thenReturn("encodedNewPassword");

        userService.changePassword("pablo@sonarfy.com", dto);

        var order = inOrder(userRepository, entityManager, jwtService, passwordEncoder);
        order.verify(userRepository).findByIdForPasswordUpdate(1L);
        order.verify(entityManager).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        order.verify(jwtService).getPasswordResetTokenIssuedAt("token-jwt");
        order.verify(passwordEncoder).matches("Old123!", "encodedOldPassword");
        assertEquals("encodedNewPassword", user.getPassword());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request ao alterar senha sem fornecer currentPassword")
    void shouldThrowBadRequestWhenCurrentPasswordIsMissing() {
        ChangePasswordDto dto = new ChangePasswordDto("token-jwt", null, "New123!", "New123!");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.changePassword("pablo@sonarfy.com", dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Current password is required!"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando nova senha e confirmação não forem iguais")
    void shouldThrowBadRequestWhenPasswordsDoNotMatchInChangePassword() {
        ChangePasswordDto dto = new ChangePasswordDto("token-jwt", "Old123!", "New123!", "Different123!");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.changePassword("pablo@sonarfy.com", dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("New password and confirmation do not match!"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 403 Forbidden quando o token pertencer a outro usuário")
    void shouldThrowForbiddenWhenTokenBelongsToAnotherUser() {
        ChangePasswordDto dto = new ChangePasswordDto("token-jwt", "Old123!", "New123!", "New123!");

        User user = new User();
        user.setId(2L);
        user.setEmail("outro@sonarfy.com");

        when(jwtService.validatePasswordResetTokenAndGetUserId("token-jwt")).thenReturn("2");
        when(userRepository.findByIdForPasswordUpdate(2L)).thenReturn(Optional.of(user));
        when(jwtService.getPasswordResetTokenIssuedAt("token-jwt")).thenReturn(Instant.now().minusSeconds(5));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.changePassword("pablo@sonarfy.com", dto)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Token does not belong to the authenticated user!"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando senha atual for incorreta")
    void shouldThrowBadRequestWhenCurrentPasswordIsIncorrect() {
        ChangePasswordDto dto = new ChangePasswordDto("token-jwt", "Wrong123!", "New123!", "New123!");

        User user = new User();
        user.setId(1L);
        user.setEmail("pablo@sonarfy.com");
        user.setPassword("encodedOldPassword");

        when(jwtService.validatePasswordResetTokenAndGetUserId("token-jwt")).thenReturn("1");
        when(userRepository.findByIdForPasswordUpdate(1L)).thenReturn(Optional.of(user));
        when(jwtService.getPasswordResetTokenIssuedAt("token-jwt")).thenReturn(Instant.now().minusSeconds(5));
        when(passwordEncoder.matches("Wrong123!", "encodedOldPassword")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.changePassword("pablo@sonarfy.com", dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Current password is incorrect!"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando a nova senha for igual à senha atual")
    void shouldThrowBadRequestWhenNewPasswordIsSameAsCurrentPassword() {
        ChangePasswordDto dto = new ChangePasswordDto("token-jwt", "Same123!", "Same123!", "Same123!");

        User user = new User();
        user.setId(1L);
        user.setEmail("pablo@sonarfy.com");
        user.setPassword("encodedOldPassword");

        when(jwtService.validatePasswordResetTokenAndGetUserId("token-jwt")).thenReturn("1");
        when(userRepository.findByIdForPasswordUpdate(1L)).thenReturn(Optional.of(user));
        when(jwtService.getPasswordResetTokenIssuedAt("token-jwt")).thenReturn(Instant.now().minusSeconds(5));
        when(passwordEncoder.matches("Same123!", "encodedOldPassword")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.changePassword("pablo@sonarfy.com", dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("New password cannot be the same as the current password!"));
        verify(userRepository, never()).save(any());
    }

    // =========================================================================
    // resetPassword tests
    // =========================================================================

    @Test
    @DisplayName("Deve redefinir senha esquecida com sucesso")
    void shouldResetPasswordSuccessfully() {
        ResetPasswordDto dto = new ResetPasswordDto("token-jwt", "NewPass123!", "NewPass123!");

        User user = new User();
        user.setId(5L);
        user.setEmail("pablo@sonarfy.com");

        when(jwtService.validatePasswordResetTokenAndGetUserId("token-jwt")).thenReturn("5");
        when(userRepository.findByIdForPasswordUpdate(5L)).thenReturn(Optional.of(user));
        when(jwtService.getPasswordResetTokenIssuedAt("token-jwt")).thenReturn(Instant.now().minusSeconds(5));
        when(passwordEncoder.encode("NewPass123!")).thenReturn("encodedNewPassword");

        userService.resetPassword(dto);

        assertEquals("encodedNewPassword", user.getPassword());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando a nova senha for igual à senha atual no resetPassword")
    void shouldThrowBadRequestWhenNewPasswordIsSameAsCurrentPasswordInResetPassword() {
        ResetPasswordDto dto = new ResetPasswordDto("token-jwt", "SamePass123!", "SamePass123!");

        User user = new User();
        user.setId(5L);
        user.setEmail("pablo@sonarfy.com");
        user.setPassword("encodedOldPassword");

        when(jwtService.validatePasswordResetTokenAndGetUserId("token-jwt")).thenReturn("5");
        when(userRepository.findByIdForPasswordUpdate(5L)).thenReturn(Optional.of(user));
        when(jwtService.getPasswordResetTokenIssuedAt("token-jwt")).thenReturn(Instant.now().minusSeconds(5));
        when(passwordEncoder.matches("SamePass123!", "encodedOldPassword")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.resetPassword(dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("New password cannot be the same as the current password!"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando token for inválido ou expirado no resetPassword")
    void shouldThrowBadRequestWhenResetTokenIsInvalidOrExpired() {
        ResetPasswordDto dto = new ResetPasswordDto("expired-token", "NewPass123!", "NewPass123!");

        when(jwtService.validatePasswordResetTokenAndGetUserId("expired-token")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.resetPassword(dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Invalid or expired token!"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Anti-Replay: Deve lançar 400 Bad Request ao tentar reutilizar token emitido antes da última alteração de senha")
    void shouldThrowBadRequestWhenTokenIssuedBeforeLastPasswordUpdate() {
        ResetPasswordDto dto = new ResetPasswordDto("used-token", "NewPass123!", "NewPass123!");

        User user = new User();
        user.setId(7L);
        user.setPasswordLastUpdateDate(OffsetDateTime.now());

        // Token emitido 5 minutos antes da última alteração de senha
        Instant tokenIssuedAt = user.getPasswordLastUpdateDate().toInstant().minusSeconds(300);

        when(jwtService.validatePasswordResetTokenAndGetUserId("used-token")).thenReturn("7");
        when(userRepository.findByIdForPasswordUpdate(7L)).thenReturn(Optional.of(user));
        when(jwtService.getPasswordResetTokenIssuedAt("used-token")).thenReturn(tokenIssuedAt);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.resetPassword(dto)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Token has already been used or is invalid!"));
        verify(userRepository, never()).save(user);
    }

    // =========================================================================
    // requestPasswordReset tests
    // =========================================================================

    @Test
    @DisplayName("Deve gerar token e disparar e-mail quando usuário existir e for verificado")
    void shouldSendPasswordResetTokenWhenUserExistsAndIsVerified() {
        User user = new User();
        user.setId(10L);
        user.setEmail("pablo@sonarfy.com");
        user.setVerified(true);

        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(user));
        when(jwtService.generatePasswordResetToken("10")).thenReturn("reset-token-123");

        userService.requestPasswordReset("pablo@sonarfy.com");

        verify(jwtService).generatePasswordResetToken("10");
        verify(authEmailService).sendPasswordResetEmail(user, "reset-token-123");
    }

    @Test
    @DisplayName("Não deve gerar token nem disparar e-mail quando usuário não for verificado")
    void shouldNotSendPasswordResetTokenWhenUserIsNotVerified() {
        User user = new User();
        user.setId(10L);
        user.setEmail("pablo@sonarfy.com");
        user.setVerified(false);

        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(user));

        userService.requestPasswordReset("pablo@sonarfy.com");

        verify(jwtService, never()).generatePasswordResetToken(any());
        verify(jwtService, never()).generateEmailConfirmationToken(any());
        verify(authEmailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    @DisplayName("Não deve gerar token nem disparar e-mail quando usuário não for encontrado")
    void shouldNotSendPasswordResetTokenWhenUserNotFound() {
        when(userRepository.findByEmailIgnoreCase("inexistente@sonarfy.com")).thenReturn(Optional.empty());

        userService.requestPasswordReset("inexistente@sonarfy.com");

        verify(jwtService, never()).generatePasswordResetToken(any());
        verify(jwtService, never()).generateEmailConfirmationToken(any());
        verify(authEmailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    @DisplayName("Recuperação pública não propaga cooldown de uma conta verificada")
    void shouldNotPropagatePasswordResetCooldown() {
        User user = verifiedPasswordResetUser();
        when(jwtService.generatePasswordResetToken("10")).thenReturn("reset-token-123");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Wait before retrying"))
                .when(authEmailService).sendPasswordResetEmail(user, "reset-token-123");

        assertDoesNotThrow(() -> userService.requestPasswordReset(user.getEmail()));

        // Evita um falso sucesso caso o método simplesmente ignore contas verificadas.
        verify(authEmailService).sendPasswordResetEmail(user, "reset-token-123");
    }

    @Test
    @DisplayName("Recuperação pública não propaga falha conhecida de entrega")
    void shouldNotPropagatePasswordResetDeliveryFailure() {
        User user = verifiedPasswordResetUser();
        when(jwtService.generatePasswordResetToken("10")).thenReturn("reset-token-123");
        doThrow(new EmailDeliveryException("Failed to deliver email.", new MailSendException("SMTP offline")))
                .when(authEmailService).sendPasswordResetEmail(user, "reset-token-123");

        assertDoesNotThrow(() -> userService.requestPasswordReset(user.getEmail()));

        verify(authEmailService).sendPasswordResetEmail(user, "reset-token-123");
    }

    @Test
    @DisplayName("Recuperação pública não esconde status diferentes de 429")
    void shouldPropagateUnexpectedPasswordResetStatus() {
        User user = verifiedPasswordResetUser();
        when(jwtService.generatePasswordResetToken("10")).thenReturn("reset-token-123");
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        doThrow(failure).when(authEmailService).sendPasswordResetEmail(user, "reset-token-123");

        assertSame(failure, assertThrows(ResponseStatusException.class,
                () -> userService.requestPasswordReset(user.getEmail())));
    }

    @Test
    @DisplayName("Recuperação pública não esconde erros inesperados do serviço de e-mail")
    void shouldPropagateUnexpectedPasswordResetFailure() {
        User user = verifiedPasswordResetUser();
        when(jwtService.generatePasswordResetToken("10")).thenReturn("reset-token-123");
        IllegalStateException failure = new IllegalStateException("Unexpected application failure");
        doThrow(failure).when(authEmailService).sendPasswordResetEmail(user, "reset-token-123");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> userService.requestPasswordReset(user.getEmail())));
    }

    @Test
    @DisplayName("Falha na consulta não é tratada como falha de entrega")
    void shouldPropagatePasswordResetLookupFailure() {
        IllegalStateException failure = new IllegalStateException("Database unavailable");
        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> userService.requestPasswordReset("pablo@sonarfy.com")));

        verifyNoInteractions(jwtService, authEmailService);
    }

    @Test
    @DisplayName("Falha na geração do token não é tratada como falha de entrega")
    void shouldPropagatePasswordResetTokenGenerationFailure() {
        User user = verifiedPasswordResetUser();
        IllegalStateException failure = new IllegalStateException("Token generation failed");
        when(jwtService.generatePasswordResetToken("10")).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> userService.requestPasswordReset(user.getEmail())));

        verifyNoInteractions(authEmailService);
    }

    private User verifiedPasswordResetUser() {
        User user = new User();
        user.setId(10L);
        user.setEmail("pablo@sonarfy.com");
        user.setVerified(true);
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        return user;
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.dto.RegisterResponseDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("Deve registrar novo usuário, enviar ativação e retornar RegisterResponseDto com registrationToken")
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
        when(jwtService.generateEmailConfirmationToken("10")).thenReturn("token-email-confirmacao");
        when(jwtService.generateRegistrationToken("10")).thenReturn("token-retificacao-pendente");

        RegisterResponseDto response = userService.newUser(dto);

        assertNotNull(response);
        assertEquals("pablo@sonarfy.com", response.email());
        assertEquals("token-retificacao-pendente", response.registrationToken());
        assertEquals(JwtService.PENDING_REGISTRATION_TTL_SECONDS, response.expiresInSeconds());

        verify(emailService, times(1)).sendEmail(eq("pablo@sonarfy.com"), any(), any());
        verify(jwtService, times(1)).generateEmailConfirmationToken("10");
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
        when(jwtService.generateEmailConfirmationToken("1")).thenReturn("mocked-jwt-token");

        String result = userService.updatePendingEmail(1L, "correto@gmail.com");

        assertEquals("correto@gmail.com", user.getEmail());
        assertEquals("Pending email updated successfully!", result);
        verify(userRepository, atLeastOnce()).save(user);
        verify(emailService, times(1)).sendEmail(eq("correto@gmail.com"), any(), any());
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
        when(jwtService.generateEmailConfirmationToken("1")).thenReturn("token-123");

        String result = userService.updatePendingEmail(1L, "novo@gmail.com");

        assertEquals("novo@gmail.com", user.getEmail());
        assertEquals("Pending email updated successfully!", result);
        verify(emailService, times(1)).sendEmail(eq("novo@gmail.com"), any(), any());
    }

    @Test
    @DisplayName("Deve lançar 429 Too Many Requests quando cooldown de 30s ainda estiver ativo")
    void shouldThrowTooManyRequestsWhenCooldownIsActive() {
        User user = new User();
        user.setId(1L);
        user.setName("Pablo");
        user.setSurname("Carmo");
        user.setEmail("errado@gmail.com");
        user.setVerified(false);
        user.setLastEmailSentAt(OffsetDateTime.now().minusSeconds(10)); // Apenas 10 segundos atrás (< 30s)

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("novo@gmail.com")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(1L, "novo@gmail.com")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertTrue(ex.getReason().contains("seconds before requesting another email"));
        verify(emailService, never()).sendEmail(any(), any(), any());
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
        verify(emailService, never()).sendEmail(any(), any(), any());
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
        verify(emailService, never()).sendEmail(any(), any(), any());
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
        verify(emailService, never()).sendEmail(any(), any(), any());
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
        verify(emailService, never()).sendEmail(any(), any(), any());
    }
}

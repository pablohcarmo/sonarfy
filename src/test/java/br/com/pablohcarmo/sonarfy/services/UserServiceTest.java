package br.com.pablohcarmo.sonarfy.services;

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
    @DisplayName("Deve atualizar e-mail pendente e disparar novo token com sucesso")
    void shouldUpdatePendingEmailSuccessfully() {
        // Arrange
        User user = new User();
        user.setId(1L);
        user.setName("Pablo");
        user.setSurname("Carmo");
        user.setHandle("pablo");
        user.setEmail("errado@gmail.com");
        user.setVerified(false);

        when(userRepository.findByHandleIgnoreCase("pablo")).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("correto@gmail.com")).thenReturn(Optional.empty());
        when(jwtService.generateEmailConfirmationToken("1")).thenReturn("mocked-jwt-token");

        // Act
        String result = userService.updatePendingEmail("pablo", "correto@gmail.com");

        // Assert
        assertEquals("correto@gmail.com", user.getEmail());
        assertEquals("Pending email updated successfully!", result);
        verify(userRepository, times(1)).save(user);
        verify(emailService, times(1)).sendEmail(eq("correto@gmail.com"), any(), any());
    }

    @Test
    @DisplayName("Deve sanitizar o handle removendo o prefixo @ e atualizar com sucesso")
    void shouldCleanHandleWithAtSignAndSucceed() {
        // Arrange
        User user = new User();
        user.setId(2L);
        user.setName("Dev");
        user.setSurname("Junior");
        user.setHandle("devjunior");
        user.setEmail("antigo@gmail.com");
        user.setVerified(false);

        when(userRepository.findByHandleIgnoreCase("devjunior")).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("novo@gmail.com")).thenReturn(Optional.empty());
        when(jwtService.generateEmailConfirmationToken("2")).thenReturn("token-123");

        // Act
        String result = userService.updatePendingEmail("@devjunior", "novo@gmail.com");

        // Assert
        assertEquals("novo@gmail.com", user.getEmail());
        assertEquals("Pending email updated successfully!", result);
        verify(userRepository, times(1)).save(user);
        verify(emailService, times(1)).sendEmail(eq("novo@gmail.com"), any(), any());
    }

    @Test
    @DisplayName("Deve lançar 400 Bad Request quando handle ou e-mail forem inválidos ou vazios")
    void shouldThrowBadRequestWhenInputsAreInvalid() {
        // Handle nulo
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail(null, "email@gmail.com")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex1.getStatusCode());

        // Handle em branco
        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail("   ", "email@gmail.com")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatusCode());

        // E-mail nulo
        ResponseStatusException ex3 = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail("pablo", null)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex3.getStatusCode());

        // E-mail em branco
        ResponseStatusException ex4 = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail("pablo", "   ")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex4.getStatusCode());

        verify(userRepository, never()).save(any());
        verify(emailService, never()).sendEmail(any(), any(), any());
    }

    @Test
    @DisplayName("Deve lançar 404 Not Found quando o handle não for localizado no repositório")
    void shouldThrowNotFoundWhenHandleDoesNotExist() {
        when(userRepository.findByHandleIgnoreCase("inexistente")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail("inexistente", "novo@gmail.com")
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
        user.setHandle("pablo");
        user.setEmail("pablo@gmail.com");
        user.setVerified(true);

        when(userRepository.findByHandleIgnoreCase("pablo")).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail("pablo", "novoteste@gmail.com")
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
        user.setHandle("pablo");
        user.setEmail("antigo@gmail.com");
        user.setVerified(false);

        User anotherUser = new User();
        anotherUser.setId(5L);
        anotherUser.setEmail("duplicado@gmail.com");

        when(userRepository.findByHandleIgnoreCase("pablo")).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("duplicado@gmail.com")).thenReturn(Optional.of(anotherUser));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                userService.updatePendingEmail("pablo", "duplicado@gmail.com")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(userRepository, never()).save(any());
        verify(emailService, never()).sendEmail(any(), any(), any());
    }
}

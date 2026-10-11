package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.dto.RegisterResponseDto;
import br.com.pablohcarmo.sonarfy.dto.UpdatePendingEmailDto;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.services.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserService userService;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthEmailService authEmailService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("POST /api/auth/register deve retornar 201 Created com RegisterResponseDto")
    void shouldRegisterUserAndReturnCreatedWithRegisterResponseDto() throws Exception {
        String jsonPayload = """
                {
                    "name": "Pablo",
                    "surname": "Carmo",
                    "handle": "pablo",
                    "email": "pablo@sonarfy.com",
                    "password": "SenhaForte123!",
                    "city": "Brasilia",
                    "country": "Brasil",
                    "birthDate": "2000-01-01"
                }
                """;

        RegisterResponseDto responseDto = new RegisterResponseDto(
                "User registered successfully! Please check your inbox.",
                "pablo@sonarfy.com",
                "mocked-registration-token",
                900L
        );

        when(userService.newUser(any(NewUserDto.class))).thenReturn(responseDto);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("pablo@sonarfy.com"))
                .andExpect(jsonPath("$.registrationToken").value("mocked-registration-token"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));

        verify(userService).newUser(any(NewUserDto.class));
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 200 OK com token Bearer válido e e-mail válido")
    void shouldReturnOkWhenTokenAndPayloadAreValid() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        when(jwtService.validateRegistrationTokenAndGetUserId("valid-registration-token")).thenReturn("1");
        when(userService.updatePendingEmail(1L, "novo@gmail.com"))
                .thenReturn("Pending email updated successfully!");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer valid-registration-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("Pending email updated successfully!"));

        verify(jwtService).validateRegistrationTokenAndGetUserId("valid-registration-token");
        verify(userService).updatePendingEmail(1L, "novo@gmail.com");
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 401 Unauthorized se header Authorization for ausente")
    void shouldReturnUnauthorizedWhenAuthorizationHeaderIsMissing() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jwtService);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 401 Unauthorized se token não iniciar com Bearer")
    void shouldReturnUnauthorizedWhenAuthorizationHeaderIsNotBearer() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Basic dXNlcjpwYXNz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jwtService);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 401 Unauthorized se registrationToken for inválido ou expirado")
    void shouldReturnUnauthorizedWhenTokenIsInvalidOrExpired() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        when(jwtService.validateRegistrationTokenAndGetUserId("expired-or-invalid-token")).thenReturn(null);

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer expired-or-invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 401 Unauthorized se userId no token não for numérico")
    void shouldReturnUnauthorizedWhenUserIdInTokenIsNotNumeric() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        when(jwtService.validateRegistrationTokenAndGetUserId("token-with-bad-id")).thenReturn("nao-numerico");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer token-with-bad-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 400 Bad Request se e-mail for vazio")
    void shouldReturnBadRequestWhenEmailIsBlank() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 400 Bad Request se formato do e-mail for inválido")
    void shouldReturnBadRequestWhenEmailIsInvalid() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("email-invalido");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 404 Not Found se usuário não for encontrado no serviço")
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        when(jwtService.validateRegistrationTokenAndGetUserId("valid-token")).thenReturn("99");
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"))
                .when(userService).updatePendingEmail(99L, "novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 400 Bad Request se conta já estiver verificada")
    void shouldReturnBadRequestWhenAccountAlreadyVerified() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        when(jwtService.validateRegistrationTokenAndGetUserId("valid-token")).thenReturn("1");
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "User already verified."))
                .when(userService).updatePendingEmail(1L, "novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 409 Conflict se novo e-mail já estiver em uso")
    void shouldReturnConflictWhenEmailAlreadyInUse() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("duplicado@gmail.com");

        when(jwtService.validateRegistrationTokenAndGetUserId("valid-token")).thenReturn("1");
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "E-mail is already in use by another account."))
                .when(userService).updatePendingEmail(1L, "duplicado@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 429 Too Many Requests quando cooldown de e-mail estiver ativo")
    void shouldReturnTooManyRequestsWhenCooldownIsActive() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("novo@gmail.com");

        when(jwtService.validateRegistrationTokenAndGetUserId("valid-token")).thenReturn("1");
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait 25 seconds before requesting another email."))
                .when(userService).updatePendingEmail(1L, "novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("POST /api/auth/resend-activation-email deve retornar 200 OK com mensagem de sucesso")
    void shouldResendActivationEmailSuccessfully() throws Exception {
        when(authEmailService.resendActivationEmail("pablo@sonarfy.com"))
                .thenReturn("Confirmation email resent successfully! Please check your inbox.");

        mockMvc.perform(post("/api/auth/resend-activation-email")
                        .param("email", "pablo@sonarfy.com"))
                .andExpect(status().isOk())
                .andExpect(content().string("Confirmation email resent successfully! Please check your inbox."));

        verify(authEmailService).resendActivationEmail("pablo@sonarfy.com");
    }

    @Test
    @DisplayName("POST /api/auth/password-reset deve retornar 200 OK com mensagem de sucesso")
    void shouldRequestPasswordResetSuccessfully() throws Exception {
        when(authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com"))
                .thenReturn("Reset password email sent successfully! Please check your inbox.");

        mockMvc.perform(post("/api/auth/password-reset")
                        .param("email", "pablo@sonarfy.com"))
                .andExpect(status().isOk())
                .andExpect(content().string("Reset password email sent successfully! Please check your inbox."));

        verify(authEmailService).sendPasswordChangeRequest("pablo@sonarfy.com");
    }
}

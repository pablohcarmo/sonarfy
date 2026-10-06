package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.UpdatePendingEmailDto;
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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserService userService;

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
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 200 OK com payload válido")
    void shouldReturnOkWhenPayloadIsValid() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("pablo", "novo@gmail.com");

        when(userService.updatePendingEmail("pablo", "novo@gmail.com"))
                .thenReturn("Pending email updated successfully!");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("Pending email updated successfully!"));
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 400 Bad Request se handle for vazio")
    void shouldReturnBadRequestWhenHandleIsBlank() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("", "novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 400 Bad Request se formato do e-mail for inválido")
    void shouldReturnBadRequestWhenEmailIsInvalid() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("pablo", "email-invalido");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 404 Not Found se usuário não for encontrado")
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("naoexiste", "novo@gmail.com");

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"))
                .when(userService).updatePendingEmail("naoexiste", "novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 400 Bad Request se conta já estiver verificada")
    void shouldReturnBadRequestWhenAccountAlreadyVerified() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("pablo", "novo@gmail.com");

        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "User already verified."))
                .when(userService).updatePendingEmail("pablo", "novo@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/auth/update-pending-email deve retornar 409 Conflict se novo e-mail já estiver em uso")
    void shouldReturnConflictWhenEmailAlreadyInUse() throws Exception {
        UpdatePendingEmailDto dto = new UpdatePendingEmailDto("pablo", "duplicado@gmail.com");

        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "E-mail is already in use by another account."))
                .when(userService).updatePendingEmail("pablo", "duplicado@gmail.com");

        mockMvc.perform(patch("/api/auth/update-pending-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }
}

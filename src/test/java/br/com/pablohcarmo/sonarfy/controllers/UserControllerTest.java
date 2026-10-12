package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.UpdatePasswordDto;
import br.com.pablohcarmo.sonarfy.dto.UpdateUserDto;
import br.com.pablohcarmo.sonarfy.dto.UserDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
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

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserService userService;

    @Mock
    private AuthEmailService authEmailService;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserController userController;

    private final Principal mockPrincipal = () -> "pablo@sonarfy.com";

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("GET /api/users/me deve retornar 200 OK com os dados do usuário autenticado")
    void shouldReturnCurrentUserProfileSuccessfully() throws Exception {
        UserDto userDto = new UserDto(
                1L,
                "Pablo",
                "Carmo",
                "pablo",
                "pablo@sonarfy.com",
                true,
                "Brasilia",
                "Brasil",
                "avatar.png",
                "wallpaper.png",
                "Software Engineer",
                LocalDate.of(2000, 1, 1),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 2, 12, 0)
        );

        when(userService.getUserRegister("pablo@sonarfy.com")).thenReturn(userDto);

        mockMvc.perform(get("/api/users/me")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Pablo"))
                .andExpect(jsonPath("$.surname").value("Carmo"))
                .andExpect(jsonPath("$.handle").value("pablo"))
                .andExpect(jsonPath("$.email").value("pablo@sonarfy.com"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.city").value("Brasilia"))
                .andExpect(jsonPath("$.country").value("Brasil"))
                .andExpect(jsonPath("$.biography").value("Software Engineer"));

        verify(userService).getUserRegister("pablo@sonarfy.com");
    }

    @Test
    @DisplayName("PUT /api/users/me deve retornar 200 OK com os dados atualizados do usuário")
    void shouldUpdateUserProfileSuccessfully() throws Exception {
        String requestJson = """
                {
                    "name": "Pablo Henrique",
                    "surname": "Carmo",
                    "handle": "pablohc",
                    "city": "Sao Paulo",
                    "country": "Brasil",
                    "birthDate": "1999-05-20"
                }
                """;

        UserDto updatedUserDto = new UserDto(
                1L,
                "Pablo Henrique",
                "Carmo",
                "pablohc",
                "pablo@sonarfy.com",
                true,
                "Sao Paulo",
                "Brasil",
                "avatar.png",
                "wallpaper.png",
                "Updated bio",
                LocalDate.of(1999, 5, 20),
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 2, 1, 14, 0)
        );

        when(userService.updateRegister(eq("pablo@sonarfy.com"), any(UpdateUserDto.class)))
                .thenReturn(updatedUserDto);

        mockMvc.perform(put("/api/users/me")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pablo Henrique"))
                .andExpect(jsonPath("$.city").value("Sao Paulo"))
                .andExpect(jsonPath("$.birthDate").value("1999-05-20"));

        verify(userService).updateRegister(eq("pablo@sonarfy.com"), any(UpdateUserDto.class));
    }

    @Test
    @DisplayName("PUT /api/users/me/password deve retornar 200 OK ao alterar senha com sucesso")
    void shouldChangePasswordSuccessfully() throws Exception {
        UpdatePasswordDto dto = new UpdatePasswordDto(
                "valid-token-123",
                "OldPass123!",
                "NewPass123!",
                "NewPass123!",
                null
        );

        doNothing().when(userService).changePassword(eq("pablo@sonarfy.com"), any(UpdatePasswordDto.class));

        mockMvc.perform(put("/api/users/me/password")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("Password updated successfully."));

        verify(userService).changePassword(eq("pablo@sonarfy.com"), any(UpdatePasswordDto.class));
    }

    @Test
    @DisplayName("PUT /api/users/me/password deve retornar 400 Bad Request quando validação do DTO falhar")
    void shouldReturnBadRequestWhenChangePasswordValidationFails() throws Exception {
        UpdatePasswordDto dto = new UpdatePasswordDto(
                "", // token em branco inválido
                null,
                "short", // senha curta < 8 caracteres
                "short",
                null
        );

        mockMvc.perform(put("/api/users/me/password")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).changePassword(any(), any());
    }

    @Test
    @DisplayName("PUT /api/users/me/password deve propagar ResponseStatusException lançada pelo UserService")
    void shouldPropagateResponseStatusExceptionFromUserService() throws Exception {
        UpdatePasswordDto dto = new UpdatePasswordDto(
                "valid-token-123",
                "WrongPass123!",
                "NewPass123!",
                "NewPass123!",
                null
        );

        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect!"))
                .when(userService).changePassword(eq("pablo@sonarfy.com"), any(UpdatePasswordDto.class));

        mockMvc.perform(put("/api/users/me/password")
                        .principal(mockPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());

        verify(userService).changePassword(eq("pablo@sonarfy.com"), any(UpdatePasswordDto.class));
    }

    @Test
    @DisplayName("POST /api/users/me/password-change-request deve retornar 200 OK e enviar token por e-mail")
    void shouldRequestPasswordChangeSuccessfully() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setEmail("pablo@sonarfy.com");

        when(userRepository.findByEmailIgnoreCase("pablo@sonarfy.com")).thenReturn(Optional.of(user));
        when(jwtService.generatePasswordResetToken("1")).thenReturn("jwt-token-reset");
        when(authEmailService.sendPasswordResetEmail(user, "jwt-token-reset"))
                .thenReturn("Reset password email sent successfully! Please check your inbox.");

        mockMvc.perform(post("/api/users/me/password-change-request")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(content().string("Reset password email sent successfully! Please check your inbox."));

        verify(authEmailService).sendPasswordResetEmail(user, "jwt-token-reset");
    }
}

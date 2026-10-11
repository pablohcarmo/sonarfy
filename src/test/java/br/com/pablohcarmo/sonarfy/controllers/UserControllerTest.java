package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.UpdateUserDto;
import br.com.pablohcarmo.sonarfy.dto.UserDto;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
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
    @DisplayName("POST /api/users/me/password-change deve retornar 200 OK ao solicitar redefinição de senha")
    void shouldRequestPasswordChangeSuccessfully() throws Exception {
        when(authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com"))
                .thenReturn("Reset password email sent successfully! Please check your inbox.");

        mockMvc.perform(post("/api/users/me/password-change")
                        .principal(mockPrincipal))
                .andExpect(status().isOk())
                .andExpect(content().string("Reset password email sent successfully! Please check your inbox."));

        verify(authEmailService).sendPasswordChangeRequest("pablo@sonarfy.com");
    }

    @Test
    @DisplayName("POST /api/users/me/password-change deve retornar 429 Too Many Requests quando cooldown de e-mail estiver ativo")
    void shouldReturnTooManyRequestsWhenPasswordChangeCooldownIsActive() throws Exception {
        when(authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com"))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait 20 seconds before requesting another email."));

        mockMvc.perform(post("/api/users/me/password-change")
                        .principal(mockPrincipal))
                .andExpect(status().isTooManyRequests());

        verify(authEmailService).sendPasswordChangeRequest("pablo@sonarfy.com");
    }

    @Test
    @DisplayName("POST /api/users/me/password-change deve retornar 404 Not Found se usuário não existir")
    void shouldReturnNotFoundWhenUserDoesNotExistForPasswordChange() throws Exception {
        when(authEmailService.sendPasswordChangeRequest("pablo@sonarfy.com"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));

        mockMvc.perform(post("/api/users/me/password-change")
                        .principal(mockPrincipal))
                .andExpect(status().isNotFound());

        verify(authEmailService).sendPasswordChangeRequest("pablo@sonarfy.com");
    }
}

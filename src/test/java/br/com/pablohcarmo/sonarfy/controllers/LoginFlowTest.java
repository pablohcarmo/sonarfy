package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Controller, BCrypt e JWT reais; repositorio simulado, sem a cadeia de seguranca HTTP.
class LoginFlowTest {
    private static final String SECRET = "login-flow-test-secret";
    private UserRepository repository;
    private PasswordEncoder encoder;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        encoder = spy(new BCryptPasswordEncoder(4));
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secretKey", SECRET);
        AuthService auth = new AuthService(repository, encoder, jwt);
        AuthController controller = new AuthController(mock(UserService.class), jwt,
                mock(AuthEmailService.class), auth);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pablo@example.com", "pablo", " @pablo "})
    void shouldLoginWithEmailOrHandleAndIssueSignedAccessToken(String login) throws Exception {
        User user = eligibleUser();
        String normalized = login.trim();
        String handle = normalized.startsWith("@") ? normalized.substring(1) : normalized;
        when(repository.findByEmailIgnoreCaseOrHandleIgnoreCase(normalized, handle))
                .thenReturn(Optional.of(user));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"Correct123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.accessToken").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.isEmptyOrNullString())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "wrong-password", "inactive", "unverified"})
    void shouldReturnSameUnauthorizedErrorWithoutToken(String scenario) throws Exception {
        User user = eligibleUser();
        if (scenario.equals("inactive")) user.setActive(false);
        if (scenario.equals("unverified")) user.setVerified(false);
        when(repository.findByEmailIgnoreCaseOrHandleIgnoreCase("pablo", "pablo"))
                .thenReturn(scenario.equals("unknown") ? Optional.empty() : Optional.of(user));
        String password = scenario.equals("wrong-password") ? "Wrong123!" : "Correct123!";

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"pablo\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(result -> assertEquals("Invalid login or password.",
                        ((ResponseStatusException) result.getResolvedException()).getReason()));
        verify(encoder).matches(eq(password), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"login\":\" \",\"password\":\"secret\"}",
            "{\"login\":\"pablo\",\"password\":\"\"}", "{\"login\":\"pablo\"}",
            "{\"password\":\"secret\"}"})
    void shouldRejectMissingOrBlankCredentialsBeforeRepositoryLookup(String payload) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
        verify(encoder, never()).matches(any(), any());
    }

    private User eligibleUser() {
        User user = new User();
        user.setId(42L);
        user.setActive(true);
        user.setVerified(true);
        user.setPassword(encoder.encode("Correct123!"));
        return user;
    }

    @ParameterizedTest
    @CsvSource({"true,a,73", "false,a,73", "true,é,37", "false,é,37"})
    void shouldRejectPasswordsExceeding72Utf8Bytes(boolean exists, String character, int count) throws Exception {
        User user = eligibleUser();
        int bytesPerCharacter = character.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        user.setPassword(encoder.encode(character.repeat(72 / bytesPerCharacter)));
        when(repository.findByEmailIgnoreCaseOrHandleIgnoreCase("pablo", "pablo"))
                .thenReturn(exists ? Optional.of(user) : Optional.empty());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"pablo\",\"password\":\"" + character.repeat(count) + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(result -> assertEquals("Invalid login or password.",
                        ((ResponseStatusException) result.getResolvedException()).getReason()));
    }

    @ParameterizedTest
    @CsvSource({"a,72", "é,36"})
    void shouldAcceptPasswordsWithExactly72Utf8Bytes(String character, int count) throws Exception {
        String password = character.repeat(count);
        User user = eligibleUser();
        user.setPassword(encoder.encode(password));
        when(repository.findByEmailIgnoreCaseOrHandleIgnoreCase("pablo", "pablo"))
                .thenReturn(Optional.of(user));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"pablo\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException.Category;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// State real; banco e troca de tokens simulados para testar a coordenação local.
class SpotifyCallbackServiceTest {
    private SpotifyAuthorizationStateService states;
    private UserRepository users;
    private AuthSpotifyService tokens;
    private SpotifyConnectionService connections;
    private SpotifyCallbackService service;
    private User user;
    private String state;

    @BeforeEach
    void setUp() {
        states = new SpotifyAuthorizationStateService(
                Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));
        users = mock(UserRepository.class);
        tokens = mock(AuthSpotifyService.class);
        connections = mock(SpotifyConnectionService.class);
        service = new SpotifyCallbackService(states, users, tokens, connections);
        user = new User();
        user.setId(42L);
        user.setActive(true);
        user.setVerified(true);
        state = states.create(user.getId());
    }

    @Test
    void shouldExchangeCodeForStateOwnerOnlyOnce() {
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenReturn(
                new SpotifyTokenResponseDto("test-access", "Bearer", 3600L, "test-refresh", "user-library-read"));

        assertDoesNotThrow(() -> service.handleCallback("test-code", state, null));
        // Repetir o callback falha antes de consultar o usuário ou trocar tokens novamente.
        assertStatus("test-code", state, null, HttpStatus.BAD_REQUEST);
        verify(users, times(1)).findById(42L);
        verify(tokens, times(1)).exchangeCode("test-code");
        verify(connections).connect(42L, new SpotifyTokenResponseDto("test-access", "Bearer", 3600L,
                "test-refresh", "user-library-read"));
        verifyNoMoreInteractions(users, tokens);
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void shouldRejectMalformedResultWithoutConsumingState(String code, String error) {
        assertStatus(code, state, error, HttpStatus.BAD_REQUEST);

        verifyNoInteractions(users, tokens);
        assertEquals(42L, states.consume(state));
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of(null, null), Arguments.of("", null), Arguments.of(" \t", null),
                Arguments.of(null, ""), Arguments.of(null, " \t"),
                Arguments.of("test-code", "access_denied"),
                Arguments.of(null, "unknown-error"), Arguments.of("test-code", "unknown-error"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "unknown-state"})
    void shouldStopOnInvalidStateBeforeUserLookup(String invalidState) {
        assertStatus("test-code", invalidState, null, HttpStatus.BAD_REQUEST);
        verifyNoInteractions(users, tokens);
        assertEquals(42L, states.consume(state));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "inactive", "unverified"})
    void shouldConsumeStateButRejectIneligibleUser(String scenario) {
        user.setActive(!scenario.equals("inactive"));
        user.setVerified(!scenario.equals("unverified"));
        when(users.findById(42L)).thenReturn(scenario.equals("deleted") ? Optional.empty() : Optional.of(user));

        assertStatus("test-code", state, null, HttpStatus.FORBIDDEN);
        assertStatus("test-code", state, null, HttpStatus.BAD_REQUEST);
        verify(users, times(1)).findById(42L);
        verifyNoInteractions(tokens);
    }

    @Test
    void shouldConsumeDeniedAttemptWithoutExchangingTokens() {
        when(users.findById(42L)).thenReturn(Optional.of(user));

        var exception = assertStatus(null, state, "access_denied", HttpStatus.FORBIDDEN);
        assertEquals("Spotify authorization was denied.", exception.getReason());
        assertStatus("test-code", state, null, HttpStatus.BAD_REQUEST);
        verify(users, times(1)).findById(42L);
        verifyNoInteractions(tokens);
    }

    @ParameterizedTest
    @CsvSource({"REJECTED,BAD_GATEWAY", "UNAVAILABLE,SERVICE_UNAVAILABLE", "TIMEOUT,GATEWAY_TIMEOUT"})
    void shouldTranslateExchangeFailureWithoutAllowingStateReuse(Category category, HttpStatus expected) {
        when(users.findById(42L)).thenReturn(Optional.of(user));
        var failure = new SpotifyIntegrationException(category);
        when(tokens.exchangeCode("test-code")).thenThrow(failure);

        var exception = assertStatus("test-code", state, null, expected);
        assertEquals(failure.getMessage(), exception.getReason());
        assertNull(exception.getCause());
        assertStatus("test-code", state, null, HttpStatus.BAD_REQUEST);
        verify(users, times(1)).findById(42L);
        verify(tokens, times(1)).exchangeCode("test-code");
        verifyNoInteractions(connections);
        verifyNoMoreInteractions(users, tokens);
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("invalidTokenResponses")
    void shouldRejectMalformedTokensWithoutAllowingStateReuse(SpotifyTokenResponseDto response) {
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenReturn(response);

        var exception = assertStatus("test-code", state, null, HttpStatus.BAD_GATEWAY);
        assertEquals("Spotify returned an invalid token response.", exception.getReason());
        assertNull(exception.getCause());
        verifyNoInteractions(connections);
        assertStatus("test-code", state, null, HttpStatus.BAD_REQUEST);
        verify(users, times(1)).findById(42L);
        verify(tokens, times(1)).exchangeCode("test-code");
        verifyNoMoreInteractions(users, tokens);
    }

    static Stream<SpotifyTokenResponseDto> invalidTokenResponses() {
        return Stream.of(
                new SpotifyTokenResponseDto(null, "Bearer", 3600L, "private-refresh", null),
                new SpotifyTokenResponseDto("", "Bearer", 3600L, null, null),
                new SpotifyTokenResponseDto(" \t", "Bearer", 3600L, null, null),
                new SpotifyTokenResponseDto("private-access", null, 3600L, null, null),
                new SpotifyTokenResponseDto("private-access", "", 3600L, null, null),
                new SpotifyTokenResponseDto("private-access", " ", 3600L, null, null),
                new SpotifyTokenResponseDto("private-access", "Basic", 3600L, null, null),
                new SpotifyTokenResponseDto("private-access", "Bearer", null, "private-refresh", null),
                new SpotifyTokenResponseDto("private-access", "Bearer", 0L, "private-refresh", null),
                new SpotifyTokenResponseDto("private-access", "Bearer", -1L, "private-refresh", null),
                new SpotifyTokenResponseDto("private-access", "Bearer", 3601L, "private-refresh", null),
                new SpotifyTokenResponseDto("private-access", "Bearer", Long.MAX_VALUE, "private-refresh", null),
                new SpotifyTokenResponseDto("private-access", "Bearer", 3600L, "", null));
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 3600L})
    void shouldAcceptExpiryBoundariesIgnoringBearerCaseAndOptionalScope(Long expiresIn) {
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenReturn(
                new SpotifyTokenResponseDto("test-access", "bearer", expiresIn, "test-refresh", null));

        assertDoesNotThrow(() -> service.handleCallback("test-code", state, null));
        verify(tokens).exchangeCode("test-code");
        verify(connections).connect(42L,
                new SpotifyTokenResponseDto("test-access", "bearer", expiresIn, "test-refresh", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"database", "encryption"})
    void shouldNotRestoreStateOrRetryAfterPersistenceFailure(String scenario) {
        var response = new SpotifyTokenResponseDto("test-access", "Bearer", 3600L, "test-refresh", null);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(tokens.exchangeCode("test-code")).thenReturn(response);
        RuntimeException failure = scenario.equals("database")
                ? new org.springframework.dao.DataAccessResourceFailureException("Test database unavailable")
                : new IllegalStateException("Cannot encrypt Spotify token.");
        doThrow(failure).when(connections).connect(42L, response);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.handleCallback("test-code", state, null)));
        assertStatus("test-code", state, null, HttpStatus.BAD_REQUEST);
        verify(tokens, times(1)).exchangeCode("test-code");
        verify(connections, times(1)).connect(42L, response);
    }

    private ResponseStatusException assertStatus(String code, String callbackState, String error, HttpStatus expected) {
        var exception = assertThrows(ResponseStatusException.class,
                () -> service.handleCallback(code, callbackState, error));
        assertEquals(expected, exception.getStatusCode());
        return exception;
    }
}

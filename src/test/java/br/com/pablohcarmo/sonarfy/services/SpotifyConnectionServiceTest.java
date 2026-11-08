package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.entities.SpotifyConnection;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import br.com.pablohcarmo.sonarfy.enums.SpotifyConnectionStatus;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotifyConnectionServiceTest {
    private final SpotifyConnectionRepository connections = mock(SpotifyConnectionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SpotifyTokenEncryptionService encryption = mock(SpotifyTokenEncryptionService.class);
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    private final SpotifyConnectionService service = new SpotifyConnectionService(connections, users, encryption, entityManager);

    @Test
    void shouldReturnDisconnectedWhenConnectionDoesNotExist() {
        when(connections.findByUserId(42L)).thenReturn(Optional.empty());

        var response = service.getConnection(42L);

        assertEquals(SpotifyConnectionStatus.DISCONNECTED, response.status());
        assertNull(response.connectedAt());
        verify(connections).findByUserId(42L);
        verifyNoMoreInteractions(connections);
        verifyNoInteractions(users, encryption);
    }

    @Test
    void disconnectInvalidatesAttemptsEvenWithoutStoredConnection() {
        var user = new User();
        when(users.findByIdForSpotifyConnection(42L)).thenReturn(Optional.of(user));
        when(connections.findByUserId(42L)).thenReturn(Optional.empty());

        service.disconnect(42L);
        service.disconnect(42L);

        assertEquals(2L, user.getSpotifyAuthorizationVersion());
        verify(connections, never()).delete(any());
        verifyNoInteractions(encryption);
    }

    @Test
    void staleAttemptCannotEncryptOrSaveTokensAfterDisconnect() {
        var user = new User();
        user.setActive(true);
        user.setVerified(true);
        user.invalidateSpotifyAuthorizations();
        when(users.findByIdForSpotifyConnection(42L)).thenReturn(Optional.of(user));

        var failure = assertThrows(ResponseStatusException.class, () -> service.connect(42L, 0L,
                new SpotifyTokenResponseDto("access", "Bearer", 3600L, "refresh", null)));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        verifyNoInteractions(connections, encryption);
    }

    @ParameterizedTest
    @ValueSource(longs = {-3600L, 3600L})
    void storedConnectionRemainsConnectedRegardlessOfAccessTokenExpiry(long secondsFromNow) {
        var connectedAt = OffsetDateTime.parse("2026-11-01T12:00:00Z");
        var now = OffsetDateTime.now();
        var connection = new SpotifyConnection(new User(), connectedAt);
        connection.updateTokens("encrypted-access", "encrypted-refresh", "Bearer",
                "user-library-read", now.plusSeconds(secondsFromNow), now);
        when(connections.findByUserId(42L)).thenReturn(Optional.of(connection));

        var response = service.getConnection(42L);

        assertEquals(SpotifyConnectionStatus.CONNECTED, response.status());
        assertEquals(connectedAt, response.connectedAt());
        verify(connections).findByUserId(42L);
        verifyNoMoreInteractions(connections);
        verifyNoInteractions(users, encryption);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, 3601L, 7200L, Long.MAX_VALUE})
    void directCallRejectsInvalidExpiryBeforeDatabaseOrEncryption(Long expiresIn) {
        var tokens = new SpotifyTokenResponseDto("access", "Bearer", expiresIn, "refresh", null);
        assertInvalid(tokens);
    }

    @Test
    void directCallRejectsNullResponseBeforeDatabaseOrEncryption() {
        assertInvalid(null);
    }

    private void assertInvalid(SpotifyTokenResponseDto tokens) {
        var failure = assertThrows(ResponseStatusException.class, () -> service.connect(42L, 0L, tokens));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertEquals("Spotify returned an invalid token response.", failure.getReason());
        verifyNoInteractions(users, connections, encryption);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 3600L})
    void directCallPersistsValidExpiryBoundariesWithOptionalScope(Long expiresIn) {
        User user = new User();
        user.setId(42L);
        user.setActive(true);
        user.setVerified(true);
        when(users.findByIdForSpotifyConnection(42L)).thenReturn(Optional.of(user));
        when(connections.findByUserId(42L)).thenReturn(Optional.empty());
        when(encryption.encrypt("access", 42L, "access")).thenReturn("encrypted-access");
        when(encryption.encrypt("refresh", 42L, "refresh")).thenReturn("encrypted-refresh");

        service.connect(42L, 0L, new SpotifyTokenResponseDto("access", "bearer", expiresIn, "refresh", null));

        var saved = ArgumentCaptor.forClass(SpotifyConnection.class);
        verify(connections).saveAndFlush(saved.capture());
        var connection = saved.getValue();
        assertEquals("encrypted-access", connection.getAccessTokenEncrypted());
        assertEquals("encrypted-refresh", connection.getRefreshTokenEncrypted());
        assertNull(connection.getScopes());
        assertEquals(expiresIn.longValue(), Duration.between(
                connection.getUpdatedAt(), connection.getAccessTokenExpiresAt()).getSeconds());
    }
}

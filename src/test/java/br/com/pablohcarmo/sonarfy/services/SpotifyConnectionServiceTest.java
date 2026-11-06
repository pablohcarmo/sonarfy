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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotifyConnectionServiceTest {
    private final SpotifyConnectionRepository connections = mock(SpotifyConnectionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SpotifyTokenEncryptionService encryption = mock(SpotifyTokenEncryptionService.class);
    private final SpotifyConnectionService service = new SpotifyConnectionService(connections, users, encryption);

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
        var failure = assertThrows(ResponseStatusException.class, () -> service.connect(42L, tokens));
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

        service.connect(42L, new SpotifyTokenResponseDto("access", "bearer", expiresIn, "refresh", null));

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

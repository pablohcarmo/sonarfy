package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotifyCatalogTokenServiceTest {
    // Reutiliza o token antes da margem e impede que um 401 antigo invalide o token novo.
    @Test void cachesUntilRenewalAndInvalidatesOnlyTheUsedToken() {
        var auth = mock(AuthSpotifyService.class);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-24T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(auth.clientCredentials()).thenReturn(new SpotifyTokenResponseDto("first", "Bearer", 3600L, null, null),
                new SpotifyTokenResponseDto("second", "Bearer", 3600L, null, null));
        var tokens = new SpotifyCatalogTokenService(auth, clock);
        assertEquals("first", tokens.get());
        assertEquals("first", tokens.get());
        verify(auth).clientCredentials();
        when(clock.instant()).thenReturn(now.plusSeconds(3570));
        assertEquals("second", tokens.get());
        tokens.invalidate("first");
        assertEquals("second", tokens.get());
        verify(auth, times(2)).clientCredentials();
        tokens.invalidate("second");
        tokens.get();
        verify(auth, times(3)).clientCredentials();
    }
}

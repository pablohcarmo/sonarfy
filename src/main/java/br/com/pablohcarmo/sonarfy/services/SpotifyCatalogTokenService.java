package br.com.pablohcarmo.sonarfy.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;

@Service
public class SpotifyCatalogTokenService {
    private final AuthSpotifyService auth;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.MIN;
    @Autowired public SpotifyCatalogTokenService(AuthSpotifyService auth) { this(auth, Clock.systemUTC()); }
    SpotifyCatalogTokenService(AuthSpotifyService auth, Clock clock) { this.auth = auth; this.clock = clock; }

    // Serializa a emissão por instância e desconta o tempo gasto na chamada de autenticação.
    public synchronized String get() {
        if (token != null && clock.instant().isBefore(refreshAt)) return token;
        var started = clock.instant();
        var response = auth.clientCredentials();
        if (!started.plusSeconds(response.expiresIn()).isAfter(clock.instant()))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, "Spotify returned an expired token.");
        refreshAt = started.plusSeconds(Math.max(0, response.expiresIn() - 30));
        token = response.accessToken();
        return token;
    }

    // Uma resposta atrasada não invalida uma credencial mais nova emitida em paralelo.
    public synchronized void invalidate(String used) {
        if (used != null && used.equals(token)) { token = null; refreshAt = Instant.MIN; }
    }
}

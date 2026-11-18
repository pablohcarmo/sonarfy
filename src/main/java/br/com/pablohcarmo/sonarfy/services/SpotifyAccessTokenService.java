package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.enums.SpotifyConnectionStatus;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import org.springframework.transaction.annotation.Propagation;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class SpotifyAccessTokenService {
    private final UserRepository users;
    private final SpotifyConnectionRepository connections;
    private final SpotifyTokenEncryptionService encryption;
    private final AuthSpotifyService auth;
    private final EntityManager em;

    public SpotifyAccessTokenService(UserRepository users, SpotifyConnectionRepository connections,
                                    SpotifyTokenEncryptionService encryption, AuthSpotifyService auth,
                                    EntityManager em) {
        this.users = users;
        this.connections = connections;
        this.encryption = encryption;
        this.auth = auth;
        this.em = em;
    }

    // Serializa entre instâncias e com callback/desconexão; vazio exige consultar o estado local.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> getValidAccessToken(Long userId) {
        var user = users.findByIdForSpotifyConnection(userId).orElseThrow(
                () -> new IllegalStateException("Spotify user unavailable."));
        em.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isEnabled()) return Optional.empty();
        var connection = connections.findByUserId(userId).orElse(null);
        if (connection == null) return Optional.empty();
        em.refresh(connection, LockModeType.PESSIMISTIC_WRITE);
        if (connection.getStatus() != SpotifyConnectionStatus.CONNECTED) return Optional.empty();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (connection.getAccessTokenExpiresAt().isAfter(now.plusSeconds(60))) {
            return Optional.of(encryption.decrypt(connection.getAccessTokenEncrypted(), userId, "access"));
        }
        SpotifyTokenResponseDto tokens;
        try {
            tokens = auth.refresh(encryption.decrypt(connection.getRefreshTokenEncrypted(), userId, "refresh"));
        } catch (AuthSpotifyService.AuthorizationRevoked revoked) {
            connection.requireReauthorization();
            connections.flush();
            return Optional.empty();
        }
        SpotifyTokenResponseValidator.validateRefresh(tokens);
        // A validade parte do início da chamada; não superestima o tempo disponível.
        connection.updateTokens(encryption.encrypt(tokens.accessToken(), userId, "access"),
                tokens.refreshToken() == null ? connection.getRefreshTokenEncrypted()
                        : encryption.encrypt(tokens.refreshToken(), userId, "refresh"),
                tokens.tokenType(), tokens.scope() == null ? connection.getScopes() : tokens.scope(),
                now.plusSeconds(tokens.expiresIn()), now);
        connections.flush();
        return Optional.of(tokens.accessToken());
    }
}

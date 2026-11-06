package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.entities.SpotifyConnection;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class SpotifyConnectionService {
    private final SpotifyConnectionRepository connections;
    private final UserRepository users;
    private final SpotifyTokenEncryptionService encryption;

    public SpotifyConnectionService(
            SpotifyConnectionRepository connections,
            UserRepository users,
            SpotifyTokenEncryptionService encryption
    ) {
        this.connections = connections;
        this.users = users;
        this.encryption = encryption;
    }

    // O callback fornece o ID recuperado do state e os tokens já validados.
    @Transactional
    public void connect(Long userId, SpotifyTokenResponseDto tokens) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Revalida se a conta está ativa e verificada após a troca dos tokens com o Spotify.
        var user = users.findByIdForSpotifyConnection(userId)
                .filter(candidate -> candidate.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "User cannot complete Spotify authorization."));
        // Reutiliza a conexão existente; cria uma apenas se o usuário ainda não tiver conexão.
        var connection = connections.findByUserId(userId)
                .orElseGet(() -> new SpotifyConnection(user, now));

        // Criptografa os dois tokens antes de atribuí-los à entidade persistida.
        // Tipo e escopos são mantidos conforme a resposta do Spotify; updated_at recebe a referência.
        connection.updateTokens(encryption.encrypt(tokens.accessToken(), userId, "access"),
                encryption.encrypt(tokens.refreshToken(), userId, "refresh"),
                tokens.tokenType(), tokens.scope(), now.plusSeconds(tokens.expiresIn()), now);
        connections.saveAndFlush(connection);
    }
}

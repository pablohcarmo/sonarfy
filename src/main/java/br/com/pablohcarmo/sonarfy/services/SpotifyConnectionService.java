package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyConnectionResponseDto;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.entities.SpotifyConnection;
import br.com.pablohcarmo.sonarfy.enums.SpotifyConnectionStatus;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
    private final EntityManager entityManager;

    public SpotifyConnectionService(
            SpotifyConnectionRepository connections,
            UserRepository users,
            SpotifyTokenEncryptionService encryption,
            EntityManager entityManager
    ) {
        this.connections = connections;
        this.users = users;
        this.encryption = encryption;
        this.entityManager = entityManager;
    }

    // O callback fornece o ID recuperado do state; chamadas diretas seguem o mesmo contrato.
    @Transactional
    public void connect(Long userId, long authorizationVersion, SpotifyTokenResponseDto tokens) {
        SpotifyTokenResponseValidator.validate(tokens);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Revalida se a conta está ativa e verificada após a troca dos tokens com o Spotify.
        var user = users.findByIdForSpotifyConnection(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "User cannot complete Spotify authorization."));
        // Recarrega inclusive entidades já presentes no contexto do request.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "User cannot complete Spotify authorization.");
        }
        if (user.getSpotifyAuthorizationVersion() != authorizationVersion) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Spotify authorization is no longer valid.");
        }
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

    @Transactional
    public void disconnect(Long userId) {
        var user = users.findByIdForSpotifyConnection(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."));
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        user.invalidateSpotifyAuthorizations();
        connections.findByUserId(userId).ifPresent(connections::delete);
        users.flush();
    }

    @Transactional(readOnly = true)
    public SpotifyConnectionResponseDto getConnection (Long userId) {
        // Consulta a conexão com o Spotify do usuário e monta a resposta para o frontend
        return connections.findByUserId(userId)

                // Se encontrar a conexão, cria um record e a data da conexão.
                .map(connection -> new SpotifyConnectionResponseDto(
                        SpotifyConnectionStatus.CONNECTED,
                        connection.getConnectedAt()
                ))
                // Se não encontrar, cria um record com DISCONNECTED e sem data.
                .orElseGet(() -> new SpotifyConnectionResponseDto(
                        SpotifyConnectionStatus.DISCONNECTED,
                        null
                ));
    }
}

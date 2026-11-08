package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.SpotifyConnection;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

@Service
public class SpotifyTokenKeyRotationService {
    private final SpotifyConnectionRepository connections;
    private final SpotifyTokenEncryptionService encryption;

    // Recebe o acesso às conexões e ao serviço de cifra usado na rotação.
    public SpotifyTokenKeyRotationService(
            SpotifyConnectionRepository connections,
            SpotifyTokenEncryptionService encryption
    ) {
        this.connections = connections;
        this.encryption = encryption;
    }

    // Recriptografa um lote limitado por transação; reexecutar retoma o processamento com segurança.
    @Transactional
    public int reencryptNextBatch(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Spotify token rotation batch size must be positive.");
        }

        var batch = connections.findNeedingTokenReencryption(
                encryption.activeCiphertextPattern(),
                PageRequest.of(0, batchSize)
        );
        for (SpotifyConnection connection : batch) {
            Long userId = connection.getUser().getId();
            String accessToken = encryption.reencrypt(
                    connection.getAccessTokenEncrypted(), userId, "access");
            String refreshToken = encryption.reencrypt(
                    connection.getRefreshTokenEncrypted(), userId, "refresh");
            connection.reencryptTokens(accessToken, refreshToken);
        }
        connections.flush();
        return batch.size();
    }

    // Conta os tokens por formato e ID da chave, sem expor o material criptográfico.
    @Transactional(readOnly = true)
    public Map<String, Long> tokenCountsByKey() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("v1:legacy", countWithPrefix("v1:"));
        for (String keyId : encryption.keyIds()) {
            counts.put("v2:" + keyId, countWithPrefix("v2:" + keyId + ":"));
        }
        return Collections.unmodifiableMap(counts);
    }

    // Conta quantos tokens ainda não usam o formato e a chave ativos.
    @Transactional(readOnly = true)
    public long countTokensNeedingReencryption() {
        return connections.countNeedingTokenReencryption(encryption.activeCiphertextPattern());
    }

    // Soma os tokens de acesso e de atualização que começam com o prefixo informado.
    private long countWithPrefix(String prefix) {
        return connections.countByAccessTokenEncryptedStartingWith(prefix)
                + connections.countByRefreshTokenEncryptedStartingWith(prefix);
    }
}

package br.com.pablohcarmo.sonarfy.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${spotify_token_encryption_rotation_enabled:false}' == 'true' "
        + "and '${spring.main.web-application-type:servlet}' == 'none'")
public class SpotifyTokenKeyRotationRunner implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(SpotifyTokenKeyRotationRunner.class);

    private final SpotifyTokenKeyRotationService rotationService;
    private final int batchSize;

    // Valida o tamanho dos lotes e guarda o serviço que executa a rotação.
    public SpotifyTokenKeyRotationRunner(
            SpotifyTokenKeyRotationService rotationService,
            @Value("${spotify_token_encryption_rotation_batch_size:100}") int batchSize
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Spotify token rotation batch size must be positive.");
        }
        this.rotationService = rotationService;
        this.batchSize = batchSize;
    }

    // Registra o progresso e processa lotes até não restarem tokens fora da chave ativa.
    @Override
    public void run(ApplicationArguments args) {
        logger.info("Spotify token key rotation started; token counts by key: {}",
                rotationService.tokenCountsByKey());

        int batchSizeProcessed;
        do {
            batchSizeProcessed = rotationService.reencryptNextBatch(batchSize);
            if (batchSizeProcessed > 0) {
                logger.info("Spotify token key rotation re-encrypted {} token values.", batchSizeProcessed * 2);
            }
        } while (batchSizeProcessed > 0);

        long remaining = rotationService.countTokensNeedingReencryption();
        if (remaining != 0) {
            throw new IllegalStateException("Spotify token key rotation stopped with tokens still needing re-encryption.");
        }
        logger.info("Spotify token key rotation completed; token counts by key: {}",
                rotationService.tokenCountsByKey());
    }
}

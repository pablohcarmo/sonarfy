package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.RotationDto;
import br.com.pablohcarmo.sonarfy.entities.RefreshToken;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.RefreshTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {
    public static final Duration INACTIVITY_TTL = Duration.ofDays(30);
    public static final long INACTIVITY_TTL_SECONDS = INACTIVITY_TTL.toSeconds();

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;


    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // Cria a sessão de um novo dispositivo e devolve o token bruto, que não é armazenado.
    @Transactional
    public String issue(User user) {
        String rawToken = newRawToken();
        refreshTokenRepository.save(new RefreshToken(user, hash(rawToken), OffsetDateTime.now().plus(INACTIVITY_TTL)));
        return rawToken;
    }

    // Valida o token, confere a conta e o substitui atomicamente por um novo.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public RotationDto rotate(String rawToken) {
        if(rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }

        String currentHash = hash(rawToken);
        OffsetDateTime now = OffsetDateTime.now();

        RefreshToken stored = refreshTokenRepository.findWithUserByTokenHash(currentHash)
                .orElseThrow(this::invalidToken);

        User user = stored.getUser();

        // Remove sessões expiradas ou de contas que deixaram de estar ativas e verificadas.
        if(!stored.getExpiresAt().isAfter(now) || !user.isEnabled()) {
            refreshTokenRepository.deleteByTokenHash(currentHash);
            throw invalidToken();
        }

        String newRawToken = newRawToken();

        // Zero linhas afetadas indica que outra requisição já usou este token.
        int updated = refreshTokenRepository.rotate(currentHash, hash(newRawToken), now.plus(INACTIVITY_TTL), now);
        if(updated != 1) {
            throw invalidToken();
        }

        return new RotationDto(user, newRawToken);
    }




    // Logout do dispositivo, idempotente para tokens ausentes ou desconhecidos.
    @Transactional
    public void revoke(String rawToken) {
        if(rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.deleteByTokenHash(hash(rawToken));
    }

    // Encerra todas as sessões do usuário, como após a troca ou redefinição de senha.
    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.deleteAllByUserId(userId);
    }

    // SHA-256 em hexadecimal, suficiente, pois o token tem 256 bits de entropia.
    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
    }
}

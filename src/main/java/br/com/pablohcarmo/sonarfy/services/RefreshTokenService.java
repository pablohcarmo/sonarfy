package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.RotationDto;
import br.com.pablohcarmo.sonarfy.entities.RefreshToken;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.RefreshTokenRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    public RefreshTokenService(RefreshTokenRepository tokens, UserRepository users,
                               EntityManager entityManager, PlatformTransactionManager manager) {
        this.tokens = tokens;
        this.users = users;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(manager);
    }

    // Cria uma sessão independente e armazena apenas o hash.
    @Transactional
    public String issue(User user) {
        User owner = lockUser(user.getId());
        if (owner == null || !owner.isEnabled()) throw invalidToken();
        String raw = newRawToken();
        tokens.save(new RefreshToken(owner, hash(raw), OffsetDateTime.now().plus(INACTIVITY_TTL)));
        return raw;
    }

    // Confirma a revogação antes de responder 401; rejeita transações externas.
    @Transactional(propagation = Propagation.NEVER)
    public RotationDto rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw invalidToken();
        RotationDto result = transaction.execute(status -> rotateLocked(hash(rawToken)));
        if (result == null) throw invalidToken();
        return result;
    }

    // O bloqueio é compartilhado por rotação e logout; replay encerra apenas esta sessão.
    private RotationDto rotateLocked(String tokenHash) {
        Long ownerId = tokens.findUserIdByTokenHash(tokenHash).orElse(null);
        if (ownerId == null) return null;
        User owner = lockUser(ownerId);
        RefreshToken stored = tokens.findWithUserByTokenHash(tokenHash).orElse(null);
        if (owner == null || stored == null) return null;
        OffsetDateTime now = OffsetDateTime.now();
        if (stored.getUsedAt() != null || !stored.getExpiresAt().isAfter(now) || !owner.isEnabled()) {
            tokens.deleteBySessionId(stored.getSessionId());
            return null;
        }
        stored.markUsed(now);
        String raw = newRawToken();
        tokens.save(new RefreshToken(owner, hash(raw), now.plus(INACTIVITY_TTL), stored.getSessionId()));
        return new RotationDto(ownerId, raw);
    }

    // Um token consumido também encerra seus sucessores, mesmo após uma rotação concorrente.
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        String tokenHash = hash(rawToken);
        Long ownerId = tokens.findUserIdByTokenHash(tokenHash).orElse(null);
        if (ownerId == null || lockUser(ownerId) == null) return;
        tokens.findWithUserByTokenHash(tokenHash)
                .ifPresent(token -> tokens.deleteBySessionId(token.getSessionId()));
    }

    // Serializa a revogação geral com a emissão e a rotação.
    @Transactional
    public void revokeAll(Long userId) {
        if (lockUser(userId) != null) tokens.deleteAllByUserId(userId);
    }

    // Recarrega a conta após esperar pelo bloqueio para validar seu estado atual.
    private User lockUser(Long userId) {
        User user = users.findByIdForRefreshToken(userId).orElse(null);
        if (user != null) entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }

    // SHA-256 é adequado para tokens aleatórios de 256 bits.
    static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // Gera o segredo que será entregue somente ao cliente.
    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
    }
}

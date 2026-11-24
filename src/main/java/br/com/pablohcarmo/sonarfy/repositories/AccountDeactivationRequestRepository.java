package br.com.pablohcarmo.sonarfy.repositories;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AccountDeactivationRequestRepository {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public AccountDeactivationRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Issued(UUID id, String token, OffsetDateTime expiresAt) {
        // O segredo só deve ser entregue ao futuro remetente, nunca a logs ou respostas genéricas.
        @Override public String toString() { return "Issued[id=" + id + ", token=hidden]"; }
    }

    // A transação do chamador deverá incluir reautenticação e enfileiramento do e-mail.
    public Issued replace(long userId) {
        if (!lockAccount(userId)) throw new IllegalArgumentException("Account unavailable.");
        String token = newToken();
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO account_deactivation_requests (user_id, request_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, statement_timestamp(), statement_timestamp() + INTERVAL '15 minutes')
                ON CONFLICT (user_id) DO UPDATE SET request_id = EXCLUDED.request_id, token_hash = EXCLUDED.token_hash,
                    issued_at = EXCLUDED.issued_at, expires_at = EXCLUDED.expires_at, consumed_at = NULL
                RETURNING expires_at
                """, (row, index) -> new Issued(id, token, row.getObject("expires_at", OffsetDateTime.class)), userId, id, hash(token));
    }

    // Cada tentativa recebe um segredo novo, sem alterar emissão, identidade ou expiração.
    public Optional<Issued> rotateForDelivery(long userId, UUID requestId) {
        if (!lockAccount(userId)) return Optional.empty();
        String token = newToken();
        return jdbc.query("""
                UPDATE account_deactivation_requests SET token_hash = ?
                WHERE user_id = ? AND request_id = ? AND consumed_at IS NULL
                    AND issued_at <= statement_timestamp() AND expires_at > statement_timestamp()
                RETURNING expires_at
                """, (row, index) -> new Issued(requestId, token, row.getObject("expires_at", OffsetDateTime.class)),
                hash(token), userId, requestId).stream().findFirst();
    }

    private static String newToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return "deactivation." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Revalida o hash após o bloqueio; o chamador deverá desativar a conta na mesma transação.
    public Optional<Long> consume(String token) {
        String hash = hash(token);
        if (hash == null) return Optional.empty();
        var owners = jdbc.query("SELECT user_id FROM account_deactivation_requests WHERE token_hash = ?",
                (row, index) -> row.getLong(1), hash);
        if (owners.isEmpty() || !lockAccount(owners.getFirst())) return Optional.empty();
        return jdbc.query("""
                UPDATE account_deactivation_requests SET consumed_at = statement_timestamp()
                WHERE user_id = ? AND token_hash = ? AND consumed_at IS NULL
                    AND issued_at <= statement_timestamp() AND expires_at > statement_timestamp() RETURNING user_id
                """, (row, index) -> row.getLong(1), owners.getFirst(), hash).stream().findFirst();
    }

    // Alterações de credenciais poderão invalidar a solicitação sob o mesmo bloqueio de conta.
    public void invalidate(long userId) {
        if (lockAccount(userId)) jdbc.update("DELETE FROM account_deactivation_requests WHERE user_id = ?", userId);
    }

    private boolean lockAccount(long userId) {
        return !jdbc.query("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE",
                (row, index) -> row.getLong(1), userId).isEmpty();
    }

    // O formato separa o propósito dos demais tokens; o banco guarda somente SHA-256 do segredo aleatório.
    private static String hash(String token) {
        if (token == null || !token.matches("deactivation\\.[A-Za-z0-9_-]{43}")) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}

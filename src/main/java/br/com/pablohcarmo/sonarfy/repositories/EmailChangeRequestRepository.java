package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.dto.EmailChangeRequestDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class EmailChangeRequestRepository {
    private final JdbcTemplate jdbc;

    public EmailChangeRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Serializa substituições por conta.
    public void replaceRequest(EmailChangeRequestDto request) {
        jdbc.queryForObject("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, request.userId());
        jdbc.update("""
                INSERT INTO email_change_requests (user_id, request_id, new_email, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET request_id = EXCLUDED.request_id,
                    new_email = EXCLUDED.new_email, issued_at = EXCLUDED.issued_at,
                    expires_at = EXCLUDED.expires_at, consumed_at = NULL
                """, request.userId(), request.id(), request.newEmail(), request.issuedAt(), request.expiresAt());
    }

    public Optional<EmailChangeRequestDto> findRequest(UUID id) {
        return jdbc.query("SELECT * FROM email_change_requests WHERE request_id = ?", (row, index) ->
                new EmailChangeRequestDto(row.getObject("request_id", UUID.class), row.getLong("user_id"), row.getString("new_email"),
                        row.getObject("issued_at", OffsetDateTime.class), row.getObject("expires_at", OffsetDateTime.class),
                        row.getObject("consumed_at", OffsetDateTime.class)), id).stream().findFirst();
    }

    // O serviço futuro deve consumir e alterar o e-mail dentro da mesma transação.
    public boolean consume(UUID id, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE email_change_requests SET consumed_at = ?
                WHERE request_id = ? AND consumed_at IS NULL AND expires_at > ?
                """, now, id, now) == 1;
    }

}

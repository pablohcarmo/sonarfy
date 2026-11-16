package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.dto.EmailChangeMailDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class EmailChangeMailRepository {
    private final JdbcTemplate jdbc;

    public EmailChangeMailRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void enqueue(UUID requestId, long userId, String kind, String recipient, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO email_change_mail_outbox (event_id, request_id, user_id, kind, recipient, available_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), requestId, userId, kind, recipient, now);
    }

    // Confirma a reserva antes do SMTP; uma reserva abandonada pode ser retomada.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<EmailChangeMailDto> claim(OffsetDateTime now, Duration lease) {
        if (lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("Lease must be positive.");
        return jdbc.query("""
                WITH candidate AS (
                    SELECT event_id FROM email_change_mail_outbox
                    WHERE delivered_at IS NULL AND available_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
                    ORDER BY available_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE email_change_mail_outbox o SET lease_id = ?, lease_until = ?, attempts = attempts + 1
                FROM candidate c WHERE o.event_id = c.event_id RETURNING o.*
                """, (row, index) -> new EmailChangeMailDto(row.getObject("event_id", UUID.class), row.getObject("request_id", UUID.class),
                row.getLong("user_id"), row.getString("kind"), row.getString("recipient"), row.getInt("attempts"),
                row.getObject("lease_id", UUID.class)), now, now, UUID.randomUUID(), now.plus(lease)).stream().findFirst();
    }

    // A identidade da reserva impede que um remetente antigo conclua trabalho retomado por outro.
    public boolean markDelivered(EmailChangeMailDto mail, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE email_change_mail_outbox SET delivered_at = ?, lease_id = NULL, lease_until = NULL
                WHERE event_id = ? AND lease_id = ? AND delivered_at IS NULL
                """, now, mail.id(), mail.leaseId()) == 1;
    }

    public boolean retryLater(EmailChangeMailDto mail, OffsetDateTime nextAttempt) {
        return jdbc.update("""
                UPDATE email_change_mail_outbox SET available_at = ?, lease_id = NULL, lease_until = NULL
                WHERE event_id = ? AND lease_id = ? AND delivered_at IS NULL
                """, nextAttempt, mail.id(), mail.leaseId()) == 1;
    }
}

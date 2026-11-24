package br.com.pablohcarmo.sonarfy.repositories;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AccountDeactivationMailRepository {
    private final JdbcTemplate db;
    private final AccountDeactivationRequestRepository requests;
    public AccountDeactivationMailRepository(JdbcTemplate db, AccountDeactivationRequestRepository requests) {
        this.db = db; this.requests = requests;
    }
    public record Delivery(long userId, UUID requestId, UUID leaseId, String recipient,
                           AccountDeactivationRequestRepository.Issued authorization) {}

    // A intenção substitui a anterior somente junto da autorização que será enviada.
    public void enqueue(long userId, UUID requestId, String recipient) {
        db.query("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", (row, index) -> row.getLong(1), userId);
        db.update("""
                INSERT INTO account_deactivation_mail_outbox (user_id, request_id, recipient) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET request_id = EXCLUDED.request_id, recipient = EXCLUDED.recipient,
                    available_at = statement_timestamp(), lease_id = NULL, lease_until = NULL, delivered_at = NULL
                """, userId, requestId, recipient);
    }

    // Confirma reserva e rotação antes do futuro SMTP; bloqueia a conta antes da outbox.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> claim() {
        var owners = db.query("""
                SELECT u.user_id FROM users u
                JOIN account_deactivation_requests r ON r.user_id = u.user_id
                JOIN account_deactivation_mail_outbox o ON o.user_id = u.user_id AND o.request_id = r.request_id
                WHERE u.active AND u.verified AND u.email = o.recipient AND r.consumed_at IS NULL
                    AND r.issued_at <= statement_timestamp() AND r.expires_at > statement_timestamp()
                    AND o.delivered_at IS NULL AND o.available_at <= statement_timestamp()
                    AND (o.lease_until IS NULL OR o.lease_until <= statement_timestamp())
                ORDER BY o.available_at, u.user_id LIMIT 1 FOR UPDATE OF u SKIP LOCKED
                """, (row, index) -> row.getLong(1));
        if (owners.isEmpty()) return Optional.empty();
        long owner = owners.getFirst();
        // Relê após o bloqueio: o snapshot da seleção pode preceder uma substituição ou invalidação.
        var current = db.query("""
                SELECT r.request_id FROM account_deactivation_requests r
                JOIN account_deactivation_mail_outbox o ON o.user_id = r.user_id AND o.request_id = r.request_id
                JOIN users u ON u.user_id = r.user_id
                WHERE r.user_id = ? AND u.active AND u.verified AND u.email = o.recipient
                    AND o.delivered_at IS NULL AND o.available_at <= statement_timestamp()
                    AND (o.lease_until IS NULL OR o.lease_until <= statement_timestamp())
                """, (row, index) -> row.getObject(1, UUID.class), owner);
        if (current.isEmpty()) return Optional.empty();
        UUID id = current.getFirst();
        var authorization = requests.rotateForDelivery(owner, id);
        if (authorization.isEmpty()) return Optional.empty();
        UUID lease = UUID.randomUUID();
        return db.query("""
                UPDATE account_deactivation_mail_outbox SET lease_id = ?, lease_until = statement_timestamp() + interval '2 minutes'
                WHERE user_id = ? AND request_id = ? RETURNING recipient
                """, (row, index) -> new Delivery(owner, id, lease, row.getString(1), authorization.get()),
                lease, owner, id).stream().findFirst();
    }

    // O relógio do banco e o vínculo atual são revalidados imediatamente antes do futuro SMTP.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean canSend(Delivery delivery) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM account_deactivation_mail_outbox o
                    JOIN account_deactivation_requests r ON r.user_id = o.user_id AND r.request_id = o.request_id
                    JOIN users u ON u.user_id = r.user_id
                    WHERE o.user_id = ? AND o.request_id = ? AND o.lease_id = ?
                        AND o.delivered_at IS NULL AND o.lease_until > statement_timestamp()
                        AND r.consumed_at IS NULL AND r.issued_at <= statement_timestamp() AND r.expires_at > statement_timestamp()
                        AND u.active AND u.verified AND u.email = o.recipient AND o.recipient = ?)
                """, Boolean.class, delivery.userId(), delivery.requestId(), delivery.leaseId(), delivery.recipient()));
    }

    // Somente a reserva atual pode concluir ou reagendar, inclusive após substituição da solicitação.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(Delivery delivery, boolean delivered) {
        db.query("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", (row, index) -> row.getLong(1), delivery.userId());
        return db.update("""
                UPDATE account_deactivation_mail_outbox SET
                    delivered_at = CASE WHEN ? THEN statement_timestamp() ELSE NULL END,
                    available_at = statement_timestamp() + interval '30 seconds', lease_id = NULL, lease_until = NULL
                WHERE user_id = ? AND request_id = ? AND lease_id = ? AND delivered_at IS NULL
                    AND lease_until > statement_timestamp()
                """, delivered, delivery.userId(), delivery.requestId(), delivery.leaseId()) == 1;
    }
}

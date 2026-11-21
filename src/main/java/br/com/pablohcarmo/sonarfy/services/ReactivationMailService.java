package br.com.pablohcarmo.sonarfy.services;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Service
public class ReactivationMailService {
    private final JdbcTemplate jdbc;
    private final EmailService email;
    private final TransactionTemplate transaction;
    private record Mail(UUID id, String recipient, UUID lease) {}

    public ReactivationMailService(JdbcTemplate jdbc, EmailService email, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.email = email; this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // O aviso só existe se a mesma transação que reativa a conta confirmar.
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long userId, String recipient) {
        jdbc.update("INSERT INTO reactivation_mail_outbox (event_id, user_id, recipient) VALUES (?, ?, ?)",
                UUID.randomUUID(), userId, recipient);
    }

    // A reserva termina antes do SMTP; processos concorrentes não compartilham a mesma reserva vigente.
    @Scheduled(fixedDelayString = "${app.reactivation.dispatch-delay-ms:5000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatch() {
        try {
            for (int i = 0; i < 10 && dispatchOne(); i++) { }
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Reactivation mail dispatch failed; failure={}", failure.getClass().getSimpleName());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean dispatchOne() {
        Mail mail = transaction.execute(status -> jdbc.query("""
                WITH candidate AS (
                    SELECT event_id FROM reactivation_mail_outbox
                    WHERE delivered_at IS NULL AND available_at <= CURRENT_TIMESTAMP
                    AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY available_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE reactivation_mail_outbox o SET lease_id = ?, lease_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes'
                FROM candidate c WHERE o.event_id = c.event_id RETURNING o.*
                """, (row, index) -> new Mail(row.getObject("event_id", UUID.class), row.getString("recipient"),
                row.getObject("lease_id", UUID.class)), UUID.randomUUID()).stream().findFirst().orElse(null));
        if (mail == null) return false;
        try {
            email.sendEmail(mail.recipient(), "Conta reativada - Sonarfy",
                    "Sua conta Sonarfy foi reativada. Se não reconhece esta ação, redefina sua senha imediatamente.");
            jdbc.update("UPDATE reactivation_mail_outbox SET delivered_at = CURRENT_TIMESTAMP, lease_id = NULL, lease_until = NULL WHERE event_id = ? AND lease_id = ?",
                    mail.id(), mail.lease());
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE reactivation_mail_outbox SET available_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes', lease_id = NULL, lease_until = NULL WHERE event_id = ? AND lease_id = ?",
                    mail.id(), mail.lease());
            LoggerFactory.getLogger(getClass()).warn("Reactivation mail deferred; failure={}", failure.getClass().getSimpleName());
        }
        return true;
    }
}

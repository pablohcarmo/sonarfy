package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;

@Component
@ConditionalOnProperty(name = "app.email-change.enabled", havingValue = "true")
public class EmailChangeDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(EmailChangeDispatcher.class);
    private final EmailChangeMailRepository mails;
    private final EmailChangeRequestRepository requests;
    private final JwtService jwt;
    private final EmailService email;
    private final String frontendUrl;

    public EmailChangeDispatcher(EmailChangeMailRepository mails, EmailChangeRequestRepository requests,
                                 JwtService jwt, EmailService email, @Value("${app.frontend-url}") String frontendUrl) {
        this.mails = mails; this.requests = requests; this.jwt = jwt; this.email = email; this.frontendUrl = frontendUrl;
    }

    // Cada reserva é confirmada antes do SMTP; a execução não mantém transação de negócio aberta.
    @Scheduled(fixedDelayString = "${app.email-change.dispatch-delay-ms:5000}")
    public void dispatch() {
        try {
            for (int i = 0; i < 10 && dispatchOne(); i++) { }
            mails.pruneDelivered(OffsetDateTime.now().minusDays(7));
        } catch (RuntimeException failure) {
            logger.warn("Email change dispatch failed; failure={}", failure.getClass().getSimpleName());
        }
    }

    public boolean dispatchOne() {
        OffsetDateTime now = OffsetDateTime.now();
        var claimed = mails.claim(now, Duration.ofMinutes(2));
        if (claimed.isEmpty()) return false;
        var mail = claimed.get();
        if (!"CONFIRM_NEW_EMAIL".equals(mail.kind())) {
            mails.retryLater(mail, now.plusMinutes(5));
            return true;
        }
        var request = requests.findRequest(mail.requestId()).orElse(null);
        if (request == null || request.consumedAt() != null || !request.expiresAt().isAfter(now)
                || request.userId() != mail.userId() || !request.newEmail().equals(mail.recipient())) {
            mails.discard(mail);
            return true;
        }
        try {
            String link = frontendUrl + "/confirm-email-change.html#token=" + jwt.generateEmailChangeToken(request);
            email.sendEmail(mail.recipient(), "Confirme seu novo e-mail - Sonarfy",
                    "Confirme a troca de e-mail no link abaixo. Se não solicitou, ignore esta mensagem.\n" + link);
            mails.markDelivered(mail, OffsetDateTime.now());
        } catch (RuntimeException failure) {
            long seconds = Math.min(300, 30L << Math.min(mail.attempts() - 1, 4));
            mails.retryLater(mail, OffsetDateTime.now().plusSeconds(seconds));
            logger.warn("Email change delivery deferred; failure={}", failure.getClass().getSimpleName());
        }
        return true;
    }
}

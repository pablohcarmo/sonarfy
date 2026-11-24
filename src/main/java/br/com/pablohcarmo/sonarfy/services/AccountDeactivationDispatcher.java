package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.AccountDeactivationMailRepository;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.account-deactivation.enabled", havingValue = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AccountDeactivationDispatcher {
    private final AccountDeactivationMailRepository mails;
    private final EmailService email;
    private final String frontendUrl;
    public AccountDeactivationDispatcher(AccountDeactivationMailRepository mails, EmailService email,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.mails = mails; this.email = email; this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    // Limita cada passagem e evita registrar mensagem de erro que possa conter autorização ou endereço.
    @Scheduled(fixedDelayString = "${app.account-deactivation.dispatch-delay-ms:5000}")
    public void dispatch() {
        try { for (int i = 0; i < 10 && dispatchOne(); i++) { } }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Deactivation dispatch failed; failure={}", failure.getClass().getSimpleName());
        }
    }

    // Reserva e releitura confirmam antes do SMTP; falhas não desfazem a solicitação aceita.
    public boolean dispatchOne() {
        var claimed = mails.claim();
        if (claimed.isEmpty()) return false;
        var delivery = claimed.get();
        if (!mails.canSend(delivery)) return true;
        try {
            email.sendEmail(delivery.recipient(), "Confirme a desativação - Sonarfy",
                    "Para desativar sua conta, abra o link e confirme explicitamente. Se não solicitou, ignore esta mensagem.\n"
                            + frontendUrl + "/confirm-account-deactivation.html#token=" + delivery.authorization().token());
            mails.complete(delivery, true);
        } catch (RuntimeException failure) {
            mails.complete(delivery, false);
            LoggerFactory.getLogger(getClass()).warn("Deactivation mail deferred; failure={}", failure.getClass().getSimpleName());
        }
        return true;
    }
}

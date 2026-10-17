package br.com.pablohcarmo.sonarfy.events;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.exceptions.EmailDeliveryException;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ActivationEmailListener {
    private static final Logger logger = LoggerFactory.getLogger(ActivationEmailListener.class);
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthEmailService authEmailService;

    public ActivationEmailListener(UserRepository userRepository, JwtService jwtService, AuthEmailService authEmailService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authEmailService = authEmailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onActivationRequested(ActivationEmailRequested event) {
        // Suspende recursos transacionais ainda associados ao commit anterior
        // O SMTP não fica numa transação escrita.

        try {
            User user = userRepository.findById(event.userId()).orElse(null);
            if (user == null || user.isVerified()) {
                return;
            }

            String token = jwtService.generateEmailConfirmationToken(user.getId().toString());
            authEmailService.sendActivationEmail(user, token);
        } catch (ResponseStatusException e) {
            // Outra solicitação pode já ter reservado o envio de e-mail, então apenas logamos a ocorrência.
            if (e.getStatusCode().value() != 429) {
                logger.error("Activation request rejected; status={}", e.getStatusCode().value());
            }
        } catch (EmailDeliveryException e) {
            logger.warn("Activation email delivery failed; retry is available after cooldown");
        } catch (RuntimeException e) {
            // Fronteira de uma ação pós-commit: o cadastro já existe e não pode ser revertido. Registra defeito inesperado sem expor o conteúdo.
            logger.error("Post-commit activation failed; failure={}", e.getClass().getSimpleName());
        }
    }
}

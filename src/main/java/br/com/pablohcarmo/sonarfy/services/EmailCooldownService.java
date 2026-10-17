package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

@Service
public class EmailCooldownService {
    private final UserRepository userRepository;
    private final long cooldownSeconds;

    public EmailCooldownService(UserRepository userRepository, @Value("${app.email.cooldown-seconds:30}") long cooldownSeconds) {
        if(cooldownSeconds <= 0) {
            throw new IllegalArgumentException("Email cooldown must be positive.");
        }

        this.userRepository = userRepository;
        this.cooldownSeconds = cooldownSeconds;
    }

    // A confirmação desta transação acontece antes de devolver o controle para o chamador, garantindo que a reserva seja persistida imediatamente.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveOrThrow(Long userId, String email, boolean verified) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = userRepository.reserveEmailSend(
                userId, email, verified, now, now.minusSeconds(cooldownSeconds));

        if(updated != 1) {
            //
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Email request unavailable due to cooldown. Please wait before trying again.");
        }
    }

}

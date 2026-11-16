package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class EmailChangeRequestService {
    public static final long TTL_SECONDS = 900;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final EmailCooldownService cooldown;
    private final EmailChangeService changes;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    public EmailChangeRequestService(UserRepository users, PasswordEncoder passwords, EmailCooldownService cooldown,
                                    EmailChangeService changes, EntityManager entityManager, PlatformTransactionManager manager) {
        this.users = users; this.passwords = passwords; this.cooldown = cooldown; this.changes = changes;
        this.entityManager = entityManager; this.transaction = new TransactionTemplate(manager);
    }

    // Reserva frequência antes da transação que bloqueia o usuário, inclusive para senha incorreta.
    @Transactional(propagation = Propagation.NEVER)
    public void request(Long userId, RequestEmailChangeDto dto) {
        User account = users.findWithPermissionById(userId).orElseThrow(this::unauthorized);
        if (!account.isEnabled()) throw unauthorized();
        cooldown.reserveOrThrow(userId, account.getEmail(), true);
        transaction.executeWithoutResult(status -> {
            User current = users.findByIdForEmailChange(userId).orElseThrow(this::unauthorized);
            entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
            if (!current.isEnabled() || dto.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                    || !passwords.matches(dto.currentPassword(), current.getPassword())) throw unauthorized();
            String target = dto.newEmail().toLowerCase(Locale.ROOT);
            if (target.equalsIgnoreCase(current.getEmail())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New email must differ from the current email.");
            }
            if (users.findByEmailIgnoreCase(target).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use.");
            }
            OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
            changes.replaceRequest(new EmailChangeRequestDto(UUID.randomUUID(), userId, target,
                    now, now.plusSeconds(TTL_SECONDS), null));
        });
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid account credentials.");
    }
}

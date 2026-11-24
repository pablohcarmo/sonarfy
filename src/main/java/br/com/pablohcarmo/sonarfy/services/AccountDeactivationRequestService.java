package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.*;
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

@Service
public class AccountDeactivationRequestService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final EmailCooldownService cooldown;
    private final AccountDeactivationRequestRepository requests;
    private final AccountDeactivationMailRepository mails;
    private final EntityManager entities;
    private final TransactionTemplate transaction;
    public AccountDeactivationRequestService(UserRepository users, PasswordEncoder passwords, EmailCooldownService cooldown,
            AccountDeactivationRequestRepository requests, AccountDeactivationMailRepository mails,
            EntityManager entities, PlatformTransactionManager manager) {
        this.users = users; this.passwords = passwords; this.cooldown = cooldown; this.requests = requests;
        this.mails = mails; this.entities = entities; this.transaction = new TransactionTemplate(manager);
    }

    // O futuro controller fornecerá somente a identidade autenticada; o serviço não retorna o segredo.
    @Transactional(propagation = Propagation.NEVER)
    public void request(long authenticatedUserId, String currentPassword) {
        if (currentPassword == null || currentPassword.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is required.");
        var account = users.findWithPermissionById(authenticatedUserId).orElseThrow(this::unauthorized);
        if (!account.isEnabled()) throw unauthorized();
        cooldown.reserveOrThrow(authenticatedUserId, account.getEmail(), true);
        transaction.executeWithoutResult(status -> {
            var current = users.findByIdForEmailChange(authenticatedUserId).orElseThrow(this::unauthorized);
            entities.refresh(current, LockModeType.PESSIMISTIC_WRITE);
            if (!current.isEnabled() || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                    || !passwords.matches(currentPassword, current.getPassword())) throw unauthorized();
            var issued = requests.replace(authenticatedUserId);
            mails.enqueue(authenticatedUserId, issued.id(), current.getEmail());
        });
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid account credentials.");
    }
}

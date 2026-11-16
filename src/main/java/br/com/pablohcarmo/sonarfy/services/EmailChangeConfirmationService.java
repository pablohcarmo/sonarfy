package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.RequestEmailChangeDto;
import br.com.pablohcarmo.sonarfy.repositories.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.Locale;

@Service
public class EmailChangeConfirmationService {
    private final JwtService jwt;
    private final UserRepository users;
    private final EmailChangeRequestRepository requests;
    private final EmailChangeMailRepository mails;
    private final RefreshTokenService refresh;
    private final EntityManager entityManager;
    private final Validator validator;

    public EmailChangeConfirmationService(JwtService jwt, UserRepository users, EmailChangeRequestRepository requests,
            EmailChangeMailRepository mails, RefreshTokenService refresh, EntityManager entityManager, Validator validator) {
        this.jwt = jwt; this.users = users; this.requests = requests; this.mails = mails; this.refresh = refresh;
        this.entityManager = entityManager; this.validator = validator;
    }

    // O bloqueio por usuário serializa confirmação, substituição e alterações de sessões.
    @Transactional
    public void confirm(String token) {
        var authorization = jwt.validateEmailChangeToken(token);
        if (authorization == null) throw invalid();
        var user = users.findByIdForEmailChange(authorization.userId()).orElseThrow(this::invalid);
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findRequest(authorization.requestId()).orElseThrow(this::invalid);
        OffsetDateTime now = OffsetDateTime.now();
        if (!user.isEnabled() || request.userId() != user.getId() || request.consumedAt() != null
                || !request.expiresAt().isAfter(now)
                || !validator.validateValue(RequestEmailChangeDto.class, "newEmail", request.newEmail()).isEmpty()) throw invalid();
        String target = request.newEmail().toLowerCase(Locale.ROOT);
        if (target.equalsIgnoreCase(user.getEmail())) throw invalid();
        if (users.findByEmailIgnoreCase(target).filter(owner -> !owner.getId().equals(user.getId())).isPresent()) throw occupied();
        String oldEmail = user.getEmail();
        // revokeAll recarrega a entidade; executar antes de modificar o e-mail gerenciado pelo JPA.
        refresh.revokeAll(user.getId());
        if (!requests.consume(request.id(), now)) throw invalid();
        user.setEmail(target);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation && "23505".equals(violation.getSQLState())
                        && "uk_users_email_ci".equals(violation.getConstraintName())) throw occupied();
            }
            throw exception;
        }
        mails.enqueue(request.id(), user.getId(), "NOTIFY_OLD_EMAIL", oldEmail, now);
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired email change authorization.");
    }
    private ResponseStatusException occupied() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use.");
    }
}

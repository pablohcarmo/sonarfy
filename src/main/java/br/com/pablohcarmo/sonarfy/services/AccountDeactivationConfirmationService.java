package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.AccountDeactivationRequestRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountDeactivationConfirmationService {
    private final AccountDeactivationRequestRepository requests;
    private final UserRepository users;
    private final RefreshTokenService refresh;
    private final EntityManager entities;
    public AccountDeactivationConfirmationService(AccountDeactivationRequestRepository requests,
            UserRepository users, RefreshTokenService refresh, EntityManager entities) {
        this.requests = requests; this.users = users; this.refresh = refresh; this.entities = entities;
    }

    // Consumo JDBC, estado JPA e revogação compartilham a transação e o bloqueio da conta.
    @Transactional
    public void confirm(String token) {
        if (token == null || token.length() > 256) throw invalid();
        long owner = requests.consume(token).orElseThrow(this::invalid);
        var user = users.findByIdForRefreshToken(owner).orElseThrow(this::invalid);
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isEnabled()) throw invalid();
        // revokeAll recarrega o usuário; deve preceder alterações na entidade gerenciada.
        refresh.revokeAll(owner);
        user.invalidateAccessTokens();
        user.invalidateSpotifyAuthorizations();
        user.setActive(false);
        users.saveAndFlush(user);
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired account deactivation authorization.");
    }
}

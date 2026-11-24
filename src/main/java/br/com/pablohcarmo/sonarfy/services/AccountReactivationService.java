package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.repositories.AccountDeactivationRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

@Service
public class AccountReactivationService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final RefreshTokenService refresh;
    private final ReactivationMailService mails;
    private final EntityManager entities;
    private final String dummyHash;
    private final AccountDeactivationRequestRepository deactivations;

    public AccountReactivationService(UserRepository users, PasswordEncoder passwords, RefreshTokenService refresh,
                                     ReactivationMailService mails, EntityManager entities, AccountDeactivationRequestRepository deactivations) {
        this.users = users; this.passwords = passwords; this.refresh = refresh; this.mails = mails; this.entities = entities;
        this.deactivations = deactivations;
        dummyHash = passwords.encode("dummy-reactivation-password");
    }

    // Compartilha o bloqueio de sessões e relacionamentos; a senha é comparada com o estado recarregado.
    @Transactional
    public void reactivate(LoginDto credentials) {
        String login = credentials.getLogin().trim();
        String handle = login.startsWith("@") ? login.substring(1) : login;
        var user = users.findByEmailIgnoreCaseOrHandleIgnoreCase(login, handle).orElse(null);
        if (user != null) entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        boolean supported = credentials.getPassword().getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwords.matches(supported ? credentials.getPassword() : "",
                user == null ? dummyHash : user.getPassword()) && supported;
        if (user == null || !matches || !user.isVerified())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login or password.");
        if (user.isActive()) return;
        // revokeAll recarrega a entidade: deve preceder as alterações de estado.
        refresh.revokeAll(user.getId());
        deactivations.invalidate(user.getId());
        user.invalidateAccessTokens();
        user.invalidateSpotifyAuthorizations();
        user.setActive(true);
        mails.enqueue(user.getId(), user.getEmail());
    }
}

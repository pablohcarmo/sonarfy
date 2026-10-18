package br.com.pablohcarmo.sonarfy.events;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.exceptions.EmailDeliveryException;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivationEmailListenerTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final AuthEmailService emails = mock(AuthEmailService.class);
    private final ActivationEmailListener listener = new ActivationEmailListener(repository, jwt, emails);

    @Test
    void ignoresDeletedAccount() {
        when(repository.findById(7L)).thenReturn(Optional.empty());
        listener.onActivationRequested(new ActivationEmailRequested(7L));
        verifyNoInteractions(jwt, emails);
    }

    @Test
    void ignoresAlreadyVerifiedAccount() {
        User user = pendingUser();
        user.setVerified(true);
        listener.onActivationRequested(new ActivationEmailRequested(7L));
        verifyNoInteractions(jwt, emails);
    }

    @Test
    void reloadsCurrentUserAndUsesConfirmationToken() {
        User user = pendingUser();
        when(jwt.generateEmailConfirmationToken("7")).thenReturn("confirmation");
        listener.onActivationRequested(new ActivationEmailRequested(7L));
        verify(emails).sendActivationEmail(user, "confirmation");
        verify(jwt, never()).generateRegistrationToken(anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 403})
    void containsPostCommitStatusFailures(int status) {
        User user = pendingUser();
        when(jwt.generateEmailConfirmationToken("7")).thenReturn("confirmation");
        doThrow(new ResponseStatusException(HttpStatus.valueOf(status)))
                .when(emails).sendActivationEmail(user, "confirmation");
        assertDoesNotThrow(() -> listener.onActivationRequested(new ActivationEmailRequested(7L)));
        verify(emails).sendActivationEmail(user, "confirmation");
    }

    @Test
    void containsDeliveryFailureAfterCommit() {
        User user = pendingUser();
        when(jwt.generateEmailConfirmationToken("7")).thenReturn("confirmation");
        doThrow(new EmailDeliveryException("Delivery failed", new RuntimeException()))
                .when(emails).sendActivationEmail(user, "confirmation");
        assertDoesNotThrow(() -> listener.onActivationRequested(new ActivationEmailRequested(7L)));
        verify(emails).sendActivationEmail(user, "confirmation");
    }

    @Test
    void containsUnexpectedFailureAfterCommit() {
        when(repository.findById(7L)).thenThrow(new IllegalStateException("Database unavailable"));
        assertDoesNotThrow(() -> listener.onActivationRequested(new ActivationEmailRequested(7L)));
        verifyNoInteractions(emails);
    }

    private User pendingUser() {
        User user = new User();
        user.setId(7L);
        user.setEmail("current@example.com");
        when(repository.findById(7L)).thenReturn(Optional.of(user));
        return user;
    }
}

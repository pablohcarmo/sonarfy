package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EmailCooldownServiceTest {
    private final UserRepository repository = mock(UserRepository.class);

    @Test
    void rejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> new EmailCooldownService(repository, 0));
        assertThrows(IllegalArgumentException.class, () -> new EmailCooldownService(repository, -1));
        verifyNoInteractions(repository);
    }

    @Test
    void usesSameInstantForReservationAndCutoff() {
        when(repository.reserveEmailSend(any(), any(), anyBoolean(), any(), any())).thenReturn(1);
        new EmailCooldownService(repository, 30).reserveOrThrow(7L, "user@example.com", false);
        ArgumentCaptor<OffsetDateTime> now = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).reserveEmailSend(eq(7L), eq("user@example.com"), eq(false),
                now.capture(), cutoff.capture());
        assertEquals(now.getValue().minusSeconds(30), cutoff.getValue());
    }

    @Test
    void rejectsReservationWithoutMatchingRow() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> new EmailCooldownService(repository, 30).reserveOrThrow(7L, "user@example.com", true));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.getStatusCode());
    }

    @Test
    void doesNotHideDatabaseFailure() {
        IllegalStateException failure = new IllegalStateException("Database unavailable");
        when(repository.reserveEmailSend(any(), any(), anyBoolean(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new EmailCooldownService(repository, 30).reserveOrThrow(7L, "user@example.com", true)));
    }
}

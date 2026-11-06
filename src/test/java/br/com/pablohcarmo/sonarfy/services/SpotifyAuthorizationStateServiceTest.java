package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotifyAuthorizationStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private Clock clock;
    private SpotifyAuthorizationStateService service;

    @BeforeEach
    void setUp() {
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        service = new SpotifyAuthorizationStateService(clock);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void shouldRejectInvalidUserWithoutStoringState(Long userId) {
        assertThrows(IllegalArgumentException.class, () -> service.create(userId));
        assertTrue(pending().isEmpty());
    }

    @Test
    void shouldStoreUrlSafeStateWithUserAndFiveMinuteExpiration() {
        String state = service.create(42L);

        assertTrue(state.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(state).length);
        assertEquals(1, pending().size());
        Object record = pending().get(state);
        assertNotNull(record);
        assertEquals(42L, ReflectionTestUtils.getField(record, "userId"));
        assertEquals(NOW.plusSeconds(300), ReflectionTestUtils.getField(record, "expiresAt"));
    }

    @Test
    void shouldKeepSeparateAttemptsForSameUser() {
        String first = service.create(42L);
        String second = service.create(42L);

        assertNotEquals(first, second);
        assertEquals(2, pending().size());
        assertTrue(pending().containsKey(first));
        assertTrue(pending().containsKey(second));
    }

    @ParameterizedTest
    @CsvSource({"299999,false", "300000,true", "300001,true"})
    void shouldCleanExpiredStatesWithoutRemovingActiveOnes(long elapsedMillis, boolean expired) {
        String first = service.create(42L);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        String active = service.create(43L);

        // Avança o relógio, sem esperar o prazo real de cinco minutos.
        when(clock.instant()).thenReturn(NOW.plusMillis(elapsedMillis));
        String latest = service.create(44L);

        assertEquals(!expired, pending().containsKey(first));
        assertTrue(pending().containsKey(active));
        assertTrue(pending().containsKey(latest));
        assertEquals(expired ? 2 : 3, pending().size());
    }

    @Test
    void shouldLimitEachUserAndAllowNewAttemptsAfterExpiration() {
        for (int i = 0; i < 3; i++) service.create(42L);

        var exception = assertThrows(ResponseStatusException.class, () -> service.create(42L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
        assertEquals(3, pending().size());
        assertDoesNotThrow(() -> service.create(43L));

        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        assertDoesNotThrow(() -> service.create(42L));
        assertEquals(1, pending().size());
    }

    @Test
    void shouldRejectAtCapacityAndAcceptAfterExpiration() {
        for (long id = 1; id <= 1000; id++) service.create(id);

        assertThrows(IllegalStateException.class, () -> service.create(43L));
        assertEquals(1000, pending().size());

        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        String state = service.create(43L);

        assertEquals(1, pending().size());
        assertEquals(43L, ReflectionTestUtils.getField(pending().get(state), "userId"));
    }

    @Test
    void shouldStartInSpringWithoutClockBean() {
        try (var context = new AnnotationConfigApplicationContext(SpotifyAuthorizationStateService.class)) {
            assertTrue(context.getBeansOfType(Clock.class).isEmpty());
            assertFalse(context.getBean(SpotifyAuthorizationStateService.class).create(42L).isBlank());
        }
    }

    @Test
    void shouldConsumeStateOnceAndPreserveOtherAttempts() {
        String state = service.create(42L);
        String other = service.create(43L);

        assertEquals(42L, service.consume(state));
        assertFalse(pending().containsKey(state));
        assertEquals(1, pending().size());
        assertBadState(state);
        assertEquals(43L, service.consume(other));
        assertTrue(pending().isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n", "unknown-state"})
    void shouldRejectInvalidStateWithoutRemovingValidAttempt(String invalidState) {
        String valid = service.create(42L);

        assertBadState(invalidState);
        assertEquals(1, pending().size());
        assertEquals(42L, service.consume(valid));
    }

    @ParameterizedTest
    @CsvSource({"299999,false", "300000,true", "300001,true"})
    void shouldCheckExpirationAtConsumption(long elapsedMillis, boolean expired) {
        String state = service.create(42L);
        // Verifica os limites do prazo sem esperar cinco minutos de verdade.
        when(clock.instant()).thenReturn(NOW.plusMillis(elapsedMillis));

        if (expired) assertBadState(state);
        else assertEquals(42L, service.consume(state));

        assertTrue(pending().isEmpty());
        assertBadState(state);
    }

    @Test
    void shouldReleaseUserCapacityAfterConsumption() {
        String state = service.create(42L);
        service.create(42L);
        service.create(42L);
        var full = assertThrows(ResponseStatusException.class, () -> service.create(42L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.getStatusCode());

        service.consume(state);
        assertDoesNotThrow(() -> service.create(42L));
        assertEquals(3, pending().size());
        var fullAgain = assertThrows(ResponseStatusException.class, () -> service.create(42L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, fullAgain.getStatusCode());
    }

    @Test
    void shouldAllowOnlyOneConcurrentConsumer() throws Exception {
        String state = service.create(42L);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        // As duas tarefas aguardam a mesma liberação antes de tentar consumir.
        Callable<Boolean> consume = () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
                assertEquals(42L, service.consume(state));
                return true;
            } catch (ResponseStatusException exception) {
                assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
                return false;
            }
        };
        try {
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertTrue(pending().isEmpty());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void assertBadState(String state) {
        var exception = assertThrows(ResponseStatusException.class, () -> service.consume(state));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private Map<?, ?> pending() {
        // Inspeciona o armazenamento sem adicionar métodos públicos apenas para os testes.
        return assertInstanceOf(Map.class, ReflectionTestUtils.getField(service, "pending"));
    }
}

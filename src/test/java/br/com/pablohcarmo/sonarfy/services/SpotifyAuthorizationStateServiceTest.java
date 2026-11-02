package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

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
    void shouldRejectAtCapacityAndAcceptAfterExpiration() {
        for (int i = 0; i < 1000; i++) service.create(42L);

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

    private Map<?, ?> pending() {
        // Inspeciona o armazenamento sem adicionar métodos públicos apenas para os testes.
        return assertInstanceOf(Map.class, ReflectionTestUtils.getField(service, "pending"));
    }
}

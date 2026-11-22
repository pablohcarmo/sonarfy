package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.EchoImportResult;
import br.com.pablohcarmo.sonarfy.dto.SpotifyAccessCredential;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.*;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import br.com.pablohcarmo.sonarfy.exceptions.StaleSpotifyAuthorizationException;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService.RetryAfterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EchoSyncServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SpotifyAccessTokenService tokenService = mock(SpotifyAccessTokenService.class);
    private final SpotifyRecentlyPlayedService spotifyApi = mock(SpotifyRecentlyPlayedService.class);
    private final EchoImportWriter importWriter = mock(EchoImportWriter.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager txManager = new PlatformTransactionManager() {
        @Override public TransactionStatus getTransaction(TransactionDefinition def) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    };

    private final EchoSyncService service = new EchoSyncService(
            users, tokenService, spotifyApi, importWriter, jdbc, txManager);

    private User activeUser;
    private SpotifyAccessCredential validCred;

    @BeforeEach
    void setup() {
        activeUser = new User();
        activeUser.setId(42L);
        activeUser.setActive(true);
        activeUser.setVerified(true);
        validCred = new SpotifyAccessCredential("secret-access", 10L, 1L, 0L, "user-read-recently-played");

        when(users.findByIdForSpotifyConnection(42L)).thenReturn(Optional.of(activeUser));
        when(jdbc.queryForList(startsWith("SELECT lease_id"), eq(java.util.UUID.class), eq(42L), any()))
                .thenAnswer(call -> List.of(call.getArgument(3, java.util.UUID.class)));
        when(jdbc.update(startsWith("INSERT INTO echo_sync_state"), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.query(eq("SELECT MAX(played_at) FROM Listens WHERE user_id = ?"),
                any(ResultSetExtractor.class), eq(42L))).thenReturn(null);
    }

    @Test
    void rejectsIneligibleUserAndActiveCooldown() {
        activeUser.setActive(false);
        var forbidden = assertThrows(ResponseStatusException.class, () -> service.sync(42L));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        activeUser.setActive(true);
        when(jdbc.update(startsWith("INSERT INTO echo_sync_state"), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT CASE"), eq(Timestamp.class), eq(42L)))
                .thenReturn(Timestamp.from(Instant.now().plusSeconds(40)));

        var cooldown = assertThrows(RetryAfterException.class, () -> service.sync(42L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, cooldown.getStatusCode());
        long retryAfter = Long.parseLong(cooldown.responseHeaders().getFirst("Retry-After"));
        assertTrue(retryAfter >= 1L && retryAfter <= 41L);
        verifyNoInteractions(tokenService, spotifyApi, importWriter);
    }

    @Test
    void rejectsMissingScopeOrDisconnectedAndReleasesLease() {
        when(tokenService.getValidCredential(42L, false))
                .thenReturn(Optional.of(new SpotifyAccessCredential("tok", 10L, 1L, 0L, "user-library-read")));

        var conflict = assertThrows(ResponseStatusException.class, () -> service.sync(42L));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        verify(jdbc).update(eq("UPDATE echo_sync_state SET lease_until = NULL, lease_id = NULL WHERE user_id = ? AND lease_id = ?"), eq(42L), any(java.util.UUID.class));
        verifyNoInteractions(spotifyApi, importWriter);
    }

    @Test
    void retriesOnceOn401AfterForcedRefreshAndMarksReauthOnSecond401() {
        var refreshed = new SpotifyAccessCredential("refreshed-access", 10L, 1L, 0L, "user-read-recently-played");
        var page = new SpotifyRecentlyPlayedResponseDto(List.of(item("t1", "2026-11-22T01:00:00Z")), null, 50);

        when(tokenService.getValidCredential(42L, false)).thenReturn(Optional.of(validCred));
        when(tokenService.getValidCredential(42L, true)).thenReturn(Optional.of(refreshed));
        when(spotifyApi.fetchPage("secret-access", 50, null)).thenThrow(new SpotifyApiException(Category.UNAUTHORIZED));
        when(spotifyApi.fetchPage("refreshed-access", 50, null)).thenReturn(page);
        when(importWriter.writeBatch(eq(42L), eq(refreshed), anyList(), any()))
                .thenReturn(new EchoImportResult(1, 0, 0, OffsetDateTime.parse("2026-11-22T01:00:00Z")));

        var result = service.sync(42L);
        assertEquals(1, result.body().imported());

        when(spotifyApi.fetchPage("refreshed-access", 50, null)).thenThrow(new SpotifyApiException(Category.UNAUTHORIZED));
        var conflict = assertThrows(ResponseStatusException.class, () -> service.sync(42L));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        verify(tokenService).markReauthorizationRequired(42L, refreshed);
    }

    @Test
    void translates403429503TimeoutAndStaleAuthorization() {
        when(tokenService.getValidCredential(42L, false)).thenReturn(Optional.of(validCred));

        when(spotifyApi.fetchPage("secret-access", 50, null))
                .thenThrow(new SpotifyApiException(Category.FORBIDDEN))
                .thenThrow(new SpotifyApiException(Category.RATE_LIMITED, 900L))
                .thenThrow(new SpotifyApiException(Category.UNAVAILABLE))
                .thenThrow(new SpotifyApiException(Category.TIMEOUT));

        assertEquals(HttpStatus.BAD_GATEWAY,
                assertThrows(ResponseStatusException.class, () -> service.sync(42L)).getStatusCode());
        var rateLimited = assertThrows(RetryAfterException.class, () -> service.sync(42L));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rateLimited.getStatusCode());
        assertEquals("900", rateLimited.responseHeaders().getFirst("Retry-After"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> service.sync(42L)).getStatusCode());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT,
                assertThrows(ResponseStatusException.class, () -> service.sync(42L)).getStatusCode());

        reset(spotifyApi);
        when(spotifyApi.fetchPage("secret-access", 50, null))
                .thenReturn(new SpotifyRecentlyPlayedResponseDto(List.of(), null, 50));
        when(importWriter.writeBatch(eq(42L), eq(validCred), anyList(), any()))
                .thenThrow(new StaleSpotifyAuthorizationException());
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> service.sync(42L)).getStatusCode());
    }

    @Test
    void paginatesWithBeforeCursorStopsOnPartialFailureAndDetectsPossibleGap() {
        var watermark = OffsetDateTime.parse("2026-11-21T20:00:00Z");
        when(jdbc.query(eq("SELECT MAX(played_at) FROM Listens WHERE user_id = ?"),
                any(ResultSetExtractor.class), eq(42L))).thenReturn(watermark);
        when(tokenService.getValidCredential(42L, false)).thenReturn(Optional.of(validCred));

        List<PlayHistoryItem> fullPage = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fullPage.add(item("t" + i, "2026-11-22T01:00:00Z"));
        }
        when(spotifyApi.fetchPage("secret-access", 50, null))
                .thenReturn(new SpotifyRecentlyPlayedResponseDto(fullPage, new Cursors("a", "1795290000000"), 50));
        when(spotifyApi.fetchPage("secret-access", 50, 1795290000000L))
                .thenThrow(new SpotifyApiException(Category.UNAVAILABLE));
        when(importWriter.writeBatch(eq(42L), eq(validCred), anyList(), any()))
                .thenReturn(new EchoImportResult(50, 0, 0, OffsetDateTime.parse("2026-11-22T01:00:00Z")));

        var result = service.sync(42L);
        assertEquals(50, result.body().fetched());
        assertEquals(50, result.body().imported());
        assertFalse(result.body().complete());
        assertTrue(result.body().possibleGap());
    }

    private static PlayHistoryItem item(String id, String playedAt) {
        var album = new AlbumObject("alb1", "Album", "2024", "year", List.of(),
                List.of(new ArtistObject("art1", "Artist")));
        var track = new TrackObject(id, "Track", 180000, false, false, "track", album,
                List.of(new ArtistObject("art1", "Artist")));
        return new PlayHistoryItem(track, OffsetDateTime.parse(playedAt));
    }
}

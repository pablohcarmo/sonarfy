package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.EchoImportResult;
import br.com.pablohcarmo.sonarfy.dto.EchoSyncResponseDto;
import br.com.pablohcarmo.sonarfy.dto.SpotifyAccessCredential;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.PlayHistoryItem;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException;
import br.com.pablohcarmo.sonarfy.exceptions.StaleSpotifyAuthorizationException;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class EchoSyncService {

    static final int PAGE_LIMIT = SpotifyRecentlyPlayedService.MAX_PAGE_LIMIT;
    static final int MAX_PAGES = 3;
    static final long COOLDOWN_SECONDS = 60L;
    static final long LEASE_SECONDS = 90L;

    private final UserRepository users;
    private final SpotifyAccessTokenService tokenService;
    private final SpotifyRecentlyPlayedService spotifyApi;
    private final EchoImportWriter importWriter;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public EchoSyncService(
            UserRepository users,
            SpotifyAccessTokenService tokenService,
            SpotifyRecentlyPlayedService spotifyApi,
            EchoImportWriter importWriter,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this.users = users;
        this.tokenService = tokenService;
        this.spotifyApi = spotifyApi;
        this.importWriter = importWriter;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // Orquestra sem transação aberta durante chamadas remotas ao Spotify.
    public EchoSyncResult sync(Long userId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID leaseId = UUID.randomUUID();
        acquireLeaseOrThrow(userId, now, leaseId);

        boolean succeeded = false;
        try {
            SpotifyAccessCredential credential;
            try {
                credential = tokenService.getValidCredential(userId, false)
                        .orElseThrow(() -> conflict("Spotify connection is not ready for syncing recent listens."));
            } catch (SpotifyIntegrationException ex) {
                throw translateTokenFailure(ex);
            }
            if (!hasRecentListensScope(credential.scopes())) {
                throw conflict("Spotify connection does not include recent listens permission.");
            }

            Optional<OffsetDateTime> watermark = findNewestPlayedAt(userId);
            FetchOutcome fetched = fetchPagesWithSingleRefresh(userId, credential, watermark);
            OffsetDateTime syncedAt = OffsetDateTime.now(ZoneOffset.UTC);

            EchoImportResult written;
            try {
                written = tx.execute(status -> {
                    // A posse é conferida sob bloqueio na mesma transação da gravação.
                    users.findByIdForSpotifyConnection(userId).orElseThrow(() -> conflict("User unavailable."));
                    var owned = jdbc.queryForList("""
                            SELECT lease_id FROM echo_sync_state
                            WHERE user_id = ? AND lease_id = ? AND lease_until > statement_timestamp()
                            FOR UPDATE
                            """, UUID.class, userId, leaseId);
                    if (owned.isEmpty()) throw conflict("Sync lease expired or was replaced.");
                    var result = importWriter.writeBatch(userId, fetched.credential(), fetched.items(), syncedAt);
                    updateCooldownOnSuccess(userId, leaseId, syncedAt);
                    return result;
                });
            } catch (StaleSpotifyAuthorizationException stale) {
                throw conflict("Spotify authorization changed during sync.");
            }

            succeeded = true;
            return new EchoSyncResult(new EchoSyncResponseDto(
                    fetched.items().size(),
                    written.imported(),
                    written.alreadyKnown(),
                    written.skipped(),
                    fetched.complete(),
                    fetched.possibleGap(),
                    written.newestPlayedAt(),
                    syncedAt
            ));
        } finally {
            if (!succeeded) {
                releaseLease(userId, leaseId);
            }
        }
    }

    private void acquireLeaseOrThrow(Long userId, OffsetDateTime now, UUID leaseId) {
        Long retryAfter = tx.execute(status -> {
            var user = users.findByIdForSpotifyConnection(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
            if (!user.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is not eligible for sync.");
            }
            OffsetDateTime leaseUntil = now.plusSeconds(LEASE_SECONDS);
            int acquired = jdbc.update("""
                    INSERT INTO echo_sync_state (user_id, lease_until, lease_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET
                        lease_until = EXCLUDED.lease_until, lease_id = EXCLUDED.lease_id
                    WHERE (echo_sync_state.lease_until IS NULL OR echo_sync_state.lease_until <= ?)
                      AND (echo_sync_state.next_allowed_at IS NULL OR echo_sync_state.next_allowed_at <= ?)
                    """, userId, leaseUntil, leaseId, now, now);
            if (acquired == 1) {
                return null;
            }
            Timestamp blockedUntil = jdbc.queryForObject("""
                    SELECT CASE
                        WHEN lease_until IS NOT NULL AND next_allowed_at IS NOT NULL THEN
                            CASE WHEN lease_until > next_allowed_at THEN lease_until ELSE next_allowed_at END
                        ELSE COALESCE(lease_until, next_allowed_at)
                    END
                    FROM echo_sync_state
                    WHERE user_id = ?
                    """, Timestamp.class, userId);
            if (blockedUntil == null) {
                return 1L;
            }
            long seconds = Duration.between(now.toInstant(), blockedUntil.toInstant()).getSeconds();
            return Math.max(1L, seconds + 1L);
        });
        if (retryAfter != null) {
            throw new RetryAfterException(HttpStatus.TOO_MANY_REQUESTS,
                    "Sync cooldown is active.", retryAfter);
        }
    }

    private Optional<OffsetDateTime> findNewestPlayedAt(Long userId) {
        return Optional.ofNullable(tx.execute(status -> jdbc.query(
                "SELECT MAX(played_at) FROM Listens WHERE user_id = ?",
                rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null,
                userId)));
    }

    private FetchOutcome fetchPagesWithSingleRefresh(
            Long userId,
            SpotifyAccessCredential initialCredential,
            Optional<OffsetDateTime> watermark
    ) {
        SpotifyAccessCredential credential = initialCredential;
        SpotifyRecentlyPlayedResponseDto firstPage;
        try {
            firstPage = spotifyApi.fetchPage(credential.accessToken(), PAGE_LIMIT, null);
        } catch (SpotifyApiException firstFailure) {
            if (firstFailure.getCategory() != SpotifyApiException.Category.UNAUTHORIZED) {
                throw translateApiFailure(firstFailure);
            }
            credential = forceRefreshOrConflict(userId);
            try {
                firstPage = spotifyApi.fetchPage(credential.accessToken(), PAGE_LIMIT, null);
            } catch (SpotifyApiException retryFailure) {
                if (retryFailure.getCategory() == SpotifyApiException.Category.UNAUTHORIZED) {
                    tokenService.markReauthorizationRequired(
                            userId, credential);
                    throw conflict("Spotify rejected the refreshed token; reauthorization is required.");
                }
                throw translateApiFailure(retryFailure);
            }
        }

        List<PlayHistoryItem> collected = new ArrayList<>();
        boolean reachedWatermark = collectPage(firstPage, watermark, collected);
        boolean complete = true;
        SpotifyRecentlyPlayedResponseDto currentPage = firstPage;
        Long previousBefore = null;
        int pagesFetched = 1;

        // Relê a janela limitada mesmo após encontrar escutas conhecidas, recuperando lotes parciais.
        while (currentPage.items().size() >= PAGE_LIMIT
                && pagesFetched < MAX_PAGES) {
            Long nextBefore = resolveBeforeCursor(currentPage, previousBefore);
            if (nextBefore == null) {
                break;
            }
            previousBefore = nextBefore;
            try {
                currentPage = spotifyApi.fetchPage(credential.accessToken(), PAGE_LIMIT, nextBefore);
            } catch (SpotifyApiException pageFailure) {
                complete = false;
                break;
            }
            pagesFetched++;
            if (currentPage.items().isEmpty()) {
                break;
            }
            reachedWatermark |= collectPage(currentPage, watermark, collected);
        }

        boolean possibleGap = watermark.isPresent() && !reachedWatermark;
        return new FetchOutcome(credential, collected, complete, possibleGap);
    }

    private SpotifyAccessCredential forceRefreshOrConflict(Long userId) {
        SpotifyAccessCredential refreshed;
        try {
            refreshed = tokenService.getValidCredential(userId, true)
                    .orElseThrow(() -> conflict("Spotify reauthorization is required."));
        } catch (SpotifyIntegrationException ex) {
            throw translateTokenFailure(ex);
        }
        if (!hasRecentListensScope(refreshed.scopes())) {
            throw conflict("Spotify connection does not include recent listens permission.");
        }
        return refreshed;
    }

    private static boolean collectPage(
            SpotifyRecentlyPlayedResponseDto page,
            Optional<OffsetDateTime> watermark,
            List<PlayHistoryItem> target
    ) {
        boolean reachedWatermark = false;
        for (PlayHistoryItem item : page.items()) {
            target.add(item);
            if (watermark.isPresent() && item != null && item.playedAt() != null
                    && !item.playedAt().isAfter(watermark.get())) {
                reachedWatermark = true;
            }
        }
        return reachedWatermark;
    }

    private static Long resolveBeforeCursor(SpotifyRecentlyPlayedResponseDto page, Long previousBefore) {
        if (page.cursors() != null && page.cursors().before() != null && !page.cursors().before().isBlank()) {
            try {
                long parsed = Long.parseLong(page.cursors().before().trim());
                if (parsed > 0L && (previousBefore == null || parsed < previousBefore)) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fallback para o menor played_at da página.
            }
        }
        OffsetDateTime oldest = null;
        for (PlayHistoryItem item : page.items()) {
            if (item != null && item.playedAt() != null && (oldest == null || item.playedAt().isBefore(oldest))) {
                oldest = item.playedAt();
            }
        }
        if (oldest == null) return null;
        long fallback = oldest.toInstant().toEpochMilli();
        return (fallback > 0L && (previousBefore == null || fallback < previousBefore)) ? fallback : null;
    }

    private void updateCooldownOnSuccess(Long userId, UUID leaseId, OffsetDateTime syncedAt) {
        jdbc.update("""
                UPDATE echo_sync_state
                SET lease_until = NULL, lease_id = NULL,
                    next_allowed_at = ?
                WHERE user_id = ? AND lease_id = ?
                """, syncedAt.plusSeconds(COOLDOWN_SECONDS), userId, leaseId);
    }

    private void releaseLease(Long userId, UUID leaseId) {
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE echo_sync_state SET lease_until = NULL, lease_id = NULL WHERE user_id = ? AND lease_id = ?",
                userId, leaseId));
    }

    private static boolean hasRecentListensScope(String scopes) {
        if (scopes == null || scopes.isBlank()) return false;
        for (String token : scopes.trim().split("\\s+")) {
            if (SpotifyAuthorizationService.RECENT_LISTENS_SCOPE.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static RuntimeException translateApiFailure(SpotifyApiException exception) {
        return switch (exception.getCategory()) {
            case UNAUTHORIZED -> conflict("Spotify authorization is no longer valid.");
            case FORBIDDEN -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Spotify denied access.");
            case RATE_LIMITED -> {
                Long retryAfter = exception.getRetryAfterSeconds();
                long delay = retryAfter == null ? COOLDOWN_SECONDS : retryAfter;
                yield new RetryAfterException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Spotify rate limit exceeded.", delay);
            }
            case UNAVAILABLE -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Spotify is unavailable.");
            case TIMEOUT -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Spotify request timed out.");
        };
    }

    private static ResponseStatusException translateTokenFailure(SpotifyIntegrationException exception) {
        HttpStatus status = switch (exception.getCategory()) {
            case REJECTED -> HttpStatus.BAD_GATEWAY;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
        };
        return new ResponseStatusException(status, exception.getMessage());
    }

    private record FetchOutcome(
            SpotifyAccessCredential credential,
            List<PlayHistoryItem> items,
            boolean complete,
            boolean possibleGap
    ) {}

    public record EchoSyncResult(EchoSyncResponseDto body) {}

    public static final class RetryAfterException extends ResponseStatusException {
        private final long retryAfterSeconds;

        public RetryAfterException(HttpStatus status, String reason, long retryAfterSeconds) {
            super(status, reason);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public HttpHeaders responseHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            return headers;
        }
    }
}

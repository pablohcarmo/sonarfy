package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.EchoImportResult;
import br.com.pablohcarmo.sonarfy.dto.SpotifyAccessCredential;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.PlayHistoryItem;
import br.com.pablohcarmo.sonarfy.enums.SpotifyConnectionStatus;
import br.com.pablohcarmo.sonarfy.exceptions.StaleSpotifyAuthorizationException;
import br.com.pablohcarmo.sonarfy.repositories.SpotifyConnectionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.EchoCatalogMapper.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class EchoImportWriter {

    private final UserRepository users;
    private final SpotifyConnectionRepository connections;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public EchoImportWriter(UserRepository users, SpotifyConnectionRepository connections,
                            EntityManager entityManager, JdbcTemplate jdbc) {
        this.users = users;
        this.connections = connections;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    // Revalida o carimbo sob bloqueio antes de gravar catálogo e escutas de forma idempotente.
    @Transactional
    public EchoImportResult writeBatch(Long userId, SpotifyAccessCredential credential,
                                       List<PlayHistoryItem> rawItems, OffsetDateTime syncedAt) {
        validateActiveAuthorization(userId, credential);

        List<NormalizedListen> valid = new ArrayList<>();
        int skipped = 0;
        if (rawItems != null) {
            for (PlayHistoryItem item : rawItems) {
                var normalized = EchoCatalogMapper.normalize(item);
                if (normalized.isPresent()) {
                    valid.add(normalized.get());
                } else {
                    skipped++;
                }
            }
        }
        if (valid.isEmpty()) {
            updateSyncSucceeded(userId, null, syncedAt);
            return new EchoImportResult(0, 0, skipped, null);
        }

        Map<String, Long> artistIds = upsertArtists(valid);
        Map<String, Long> albumIds = upsertAlbums(valid, artistIds);
        Map<String, Long> trackIds = upsertTracks(valid, albumIds);
        syncTrackArtists(valid, trackIds, artistIds);

        int imported = 0;
        OffsetDateTime oldest = null;
        OffsetDateTime newest = null;
        for (NormalizedListen listen : valid) {
            int inserted = jdbc.update("""
                    INSERT INTO Listens (user_id, album_id, track_id, spotify_track_id, played_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, spotify_track_id, played_at) DO NOTHING
                    """,
                    userId,
                    albumIds.get(listen.album().spotifyId()),
                    trackIds.get(listen.trackSpotifyId()),
                    listen.trackSpotifyId(),
                    listen.playedAt());
            imported += inserted;
            if (oldest == null || listen.playedAt().isBefore(oldest)) oldest = listen.playedAt();
            if (newest == null || listen.playedAt().isAfter(newest)) newest = listen.playedAt();
        }
        updateSyncSucceeded(userId, oldest, syncedAt);
        return new EchoImportResult(imported, valid.size() - imported, skipped, newest);
    }

    private void validateActiveAuthorization(Long userId, SpotifyAccessCredential credential) {
        if (userId == null || credential == null || credential.connectionId() == null) {
            throw new StaleSpotifyAuthorizationException();
        }
        // Mantém a mesma ordem de bloqueio (Users -> Spotify_Connection) usada pelo OAuth e refresh.
        var user = users.findByIdForSpotifyConnection(userId)
                .orElseThrow(StaleSpotifyAuthorizationException::new);
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isEnabled()
                || user.getSpotifyAuthorizationVersion() != credential.spotifyAuthorizationVersion()) {
            throw new StaleSpotifyAuthorizationException();
        }
        var connection = connections.findByUserId(userId)
                .orElseThrow(StaleSpotifyAuthorizationException::new);
        entityManager.refresh(connection, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(connection.getId(), credential.connectionId())
                || connection.getStatus() != SpotifyConnectionStatus.CONNECTED
                || connection.getAuthorizationGeneration() != credential.authorizationGeneration()
                || !connection.hasScope(SpotifyAuthorizationService.RECENT_LISTENS_SCOPE)) {
            throw new StaleSpotifyAuthorizationException();
        }
    }

    private Map<String, Long> upsertArtists(List<NormalizedListen> listens) {
        TreeMap<String, NormalizedArtist> sorted = new TreeMap<>();
        for (NormalizedListen listen : listens) {
            sorted.putIfAbsent(listen.album().primaryArtistSpotifyId(),
                    new NormalizedArtist(listen.album().primaryArtistSpotifyId(), listen.album().primaryArtistName()));
            for (NormalizedArtist artist : listen.artists()) {
                sorted.put(artist.spotifyId(), artist);
            }
        }
        Map<String, Long> ids = new HashMap<>();
        for (NormalizedArtist artist : sorted.values()) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO Artists (spotify_id, name)
                    VALUES (?, ?)
                    ON CONFLICT (spotify_id) DO UPDATE SET name = EXCLUDED.name
                    RETURNING artist_id
                    """, Long.class, artist.spotifyId(), artist.name());
            ids.put(artist.spotifyId(), id);
        }
        return ids;
    }

    private Map<String, Long> upsertAlbums(List<NormalizedListen> listens, Map<String, Long> artistIds) {
        TreeMap<String, NormalizedAlbum> sorted = new TreeMap<>();
        for (NormalizedListen listen : listens) {
            sorted.putIfAbsent(listen.album().spotifyId(), listen.album());
        }
        Map<String, Long> ids = new HashMap<>();
        for (NormalizedAlbum album : sorted.values()) {
            Long artistId = artistIds.get(album.primaryArtistSpotifyId());
            Long id = jdbc.queryForObject("""
                    INSERT INTO Albums (artist_id, spotify_id, name, cover, release_on, release_date_precision)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (spotify_id) DO UPDATE SET
                        artist_id = EXCLUDED.artist_id,
                        name = EXCLUDED.name,
                        cover = COALESCE(EXCLUDED.cover, Albums.cover),
                        release_on = COALESCE(EXCLUDED.release_on, Albums.release_on),
                        release_date_precision = COALESCE(EXCLUDED.release_date_precision, Albums.release_date_precision)
                    RETURNING album_id
                    """,
                    new Object[]{artistId, album.spotifyId(), album.name(), album.coverUrl(),
                            album.releaseOn(), album.releasePrecision()},
                    new int[]{Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR},
                    Long.class);
            ids.put(album.spotifyId(), id);
        }
        return ids;
    }

    private Map<String, Long> upsertTracks(List<NormalizedListen> listens, Map<String, Long> albumIds) {
        TreeMap<String, NormalizedListen> sorted = new TreeMap<>();
        for (NormalizedListen listen : listens) {
            sorted.putIfAbsent(listen.trackSpotifyId(), listen);
        }
        Map<String, Long> ids = new HashMap<>();
        for (NormalizedListen track : sorted.values()) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO Tracks (spotify_id, album_id, name, duration_ms, explicit)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (spotify_id) DO UPDATE SET
                        album_id = EXCLUDED.album_id,
                        name = EXCLUDED.name,
                        duration_ms = EXCLUDED.duration_ms,
                        explicit = EXCLUDED.explicit
                    RETURNING track_id
                    """,
                    new Object[]{track.trackSpotifyId(), albumIds.get(track.album().spotifyId()),
                            track.trackName(), track.durationMs(), track.explicit()},
                    new int[]{Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.BOOLEAN},
                    Long.class);
            ids.put(track.trackSpotifyId(), id);
        }
        return ids;
    }

    private void syncTrackArtists(List<NormalizedListen> listens, Map<String, Long> trackIds, Map<String, Long> artistIds) {
        TreeMap<Long, List<NormalizedArtist>> byTrackId = new TreeMap<>();
        for (NormalizedListen listen : listens) {
            byTrackId.putIfAbsent(trackIds.get(listen.trackSpotifyId()), listen.artists());
        }
        for (var entry : byTrackId.entrySet()) {
            Long trackId = entry.getKey();
            jdbc.update("DELETE FROM Track_Artists WHERE track_id = ?", trackId);
            short position = 0;
            for (NormalizedArtist artist : entry.getValue()) {
                jdbc.update("INSERT INTO Track_Artists (track_id, position, artist_id) VALUES (?, ?, ?)",
                        trackId, position++, artistIds.get(artist.spotifyId()));
            }
        }
    }

    private void updateSyncSucceeded(Long userId, OffsetDateTime oldestInBatch, OffsetDateTime syncedAt) {
        jdbc.update("""
                INSERT INTO echo_sync_state (user_id, last_succeeded_at, history_starts_at)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    last_succeeded_at = EXCLUDED.last_succeeded_at,
                    history_starts_at = CASE
                        WHEN echo_sync_state.history_starts_at IS NULL THEN EXCLUDED.history_starts_at
                        WHEN EXCLUDED.history_starts_at IS NULL THEN echo_sync_state.history_starts_at
                        ELSE LEAST(echo_sync_state.history_starts_at, EXCLUDED.history_starts_at)
                    END
                """,
                new Object[]{userId, syncedAt, oldestInBatch},
                new int[]{Types.BIGINT, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE});
    }
}

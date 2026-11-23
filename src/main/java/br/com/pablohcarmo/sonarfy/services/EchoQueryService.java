package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.EchoesPageResponseDto;
import br.com.pablohcarmo.sonarfy.dto.EchoAlbumsPageResponseDto;
import br.com.pablohcarmo.sonarfy.dto.EchoesPageResponseDto.*;
import br.com.pablohcarmo.sonarfy.services.EchoCursorCodec.DecodedCursor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EchoQueryService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final EchoCursorCodec cursorCodec;

    public EchoQueryService(JdbcTemplate jdbc, EchoCursorCodec cursorCodec) {
        this.jdbc = jdbc;
        this.cursorCodec = cursorCodec;
    }

    private record ListenRow(
            long listenId,
            OffsetDateTime playedAt,
            long trackId,
            String trackSpotifyId,
            String trackName,
            Integer durationMs,
            boolean explicit,
            String albumSpotifyId,
            String albumName,
            String albumCoverUrl
    ) {}

    // Consulta escutas importadas do usuário autenticado sem acionar o Spotify nem gravar estado.
    @Transactional(readOnly = true)
    public EchoesPageResponseDto list(Long userId, int limit, String cursor) {
        return listHistory(userId, limit, cursor, null);
    }

    // Consulta somente as escutas do álbum, com cursor vinculado ao seu identificador.
    @Transactional(readOnly = true)
    public EchoesPageResponseDto albumHistory(Long userId, String albumSpotifyId, int limit, String cursor) {
        return listHistory(userId, limit, cursor, albumSpotifyId);
    }

    private EchoesPageResponseDto listHistory(Long userId, int limit, String cursor, String albumSpotifyId) {
        EchoCursorCodec codec = albumSpotifyId == null ? cursorCodec : cursorCodec.scoped("album:" + albumSpotifyId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Limit must be between 1 and " + MAX_LIMIT + ".");
        }
        DecodedCursor decoded = cursor == null ? null : codec.decode(userId, cursor);

        List<ListenRow> fetched = fetchListenRows(userId, limit + 1, decoded, albumSpotifyId);
        boolean hasMore = fetched.size() > limit;
        List<ListenRow> pageRows = hasMore ? fetched.subList(0, limit) : fetched;

        Map<Long, List<EchoArtistDto>> artistsByTrack = fetchArtistsByTrack(pageRows);
        List<EchoListenItemDto> items = pageRows.stream()
                .map(row -> new EchoListenItemDto(
                        row.listenId(),
                        row.playedAt(),
                        new EchoTrackDto(row.trackSpotifyId(), row.trackName(), row.durationMs(), row.explicit()),
                        artistsByTrack.getOrDefault(row.trackId(), List.of()),
                        new EchoAlbumDto(row.albumSpotifyId(), row.albumName(), row.albumCoverUrl())
                ))
                .toList();

        String nextCursor = hasMore
                ? codec.encode(userId, pageRows.getLast().playedAt(), pageRows.getLast().listenId())
                : null;
        EchoSyncMetadataDto syncMetadata = fetchSyncMetadata(userId);
        return new EchoesPageResponseDto(items, nextCursor, syncMetadata);
    }

    // Agrupa todo o histórico antes de aplicar o cursor sobre a última escuta de cada álbum.
    @Transactional(readOnly = true)
    public EchoAlbumsPageResponseDto albums(Long userId, int limit, String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 50.");
        }
        var codec = cursorCodec.scoped("albums");
        var decoded = cursor == null ? null : codec.decode(userId, cursor);
        record AlbumRow(EchoAlbumsPageResponseDto.AlbumItem item, long listenId) {}
        List<Object> args = new ArrayList<>();
        args.add(userId);
        String boundary = "";
        if (decoded != null) {
            boundary = "WHERE (played_at, listen_id) < (?, ?)";
            args.add(decoded.playedAt());
            args.add(decoded.listenId());
        }
        args.add(limit + 1);
        var rows = jdbc.query("""
                WITH ranked AS (
                    SELECT a.album_id, l.user_id, a.spotify_id, a.name, a.cover, l.played_at, l.listen_id,
                           COUNT(*) OVER (PARTITION BY a.album_id) AS listen_count,
                           ROW_NUMBER() OVER (PARTITION BY a.album_id
                               ORDER BY l.played_at DESC, l.listen_id DESC) AS position
                    FROM Listens l
                    JOIN Tracks t ON t.track_id = l.track_id
                    JOIN Albums a ON a.album_id = t.album_id
                    WHERE l.user_id = ?
                ), latest AS (SELECT * FROM ranked WHERE position = 1)
                SELECT latest.*, r.review_id FROM latest
                LEFT JOIN Reviews r ON r.album_id = latest.album_id AND r.user_id = latest.user_id
                %s
                ORDER BY played_at DESC, listen_id DESC LIMIT ?
                """.formatted(boundary), (rs, n) -> new AlbumRow(
                new EchoAlbumsPageResponseDto.AlbumItem(
                        new EchoAlbumDto(rs.getString("spotify_id"), rs.getString("name"), rs.getString("cover")),
                        toUtc(rs.getObject("played_at", OffsetDateTime.class)), rs.getLong("listen_count"),
                        rs.getObject("review_id", Long.class) != null, rs.getObject("review_id", Long.class)),
                rs.getLong("listen_id")), args.toArray());
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        String next = more ? codec.encode(userId, page.getLast().item().lastPlayedAt(), page.getLast().listenId()) : null;
        return new EchoAlbumsPageResponseDto(page.stream().map(AlbumRow::item).toList(), next, fetchSyncMetadata(userId));
    }

    private List<ListenRow> fetchListenRows(long userId, int fetchLimit, DecodedCursor cursor, String albumSpotifyId) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        String filters = "";
        if (albumSpotifyId != null) {
            filters += " AND a.spotify_id = ?";
            args.add(albumSpotifyId);
        }
        if (cursor != null) {
            filters += " AND (l.played_at, l.listen_id) < (?, ?)";
            args.add(cursor.playedAt());
            args.add(cursor.listenId());
        }
        args.add(fetchLimit);
        return jdbc.query("""
                SELECT l.listen_id, l.played_at,
                       t.track_id, t.spotify_id, t.name, t.duration_ms, t.explicit,
                       a.spotify_id, a.name, a.cover
                FROM Listens l
                JOIN Tracks t ON t.track_id = l.track_id
                JOIN Albums a ON a.album_id = t.album_id
                WHERE l.user_id = ? %s
                ORDER BY l.played_at DESC, l.listen_id DESC LIMIT ?
                """.formatted(filters), (rs, rowNum) -> mapListenRow(rs), args.toArray());
    }

    private static ListenRow mapListenRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ListenRow(
                rs.getLong(1),
                toUtc(rs.getObject(2, OffsetDateTime.class)),
                rs.getLong(3),
                rs.getString(4),
                rs.getString(5),
                rs.getObject(6, Integer.class),
                rs.getBoolean(7),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10)
        );
    }

    private Map<Long, List<EchoArtistDto>> fetchArtistsByTrack(List<ListenRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> trackIds = rows.stream().map(ListenRow::trackId).distinct().toList();
        String placeholders = trackIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<Long, List<EchoArtistDto>> grouped = new HashMap<>();
        jdbc.query("""
                SELECT ta.track_id, ar.spotify_id, ar.name
                FROM Track_Artists ta
                JOIN Artists ar ON ar.artist_id = ta.artist_id
                WHERE ta.track_id IN (%s)
                ORDER BY ta.track_id ASC, ta.position ASC
                """.formatted(placeholders),
                rs -> {
                    long trackId = rs.getLong(1);
                    grouped.computeIfAbsent(trackId, ignored -> new ArrayList<>())
                            .add(new EchoArtistDto(rs.getString(2), rs.getString(3)));
                },
                trackIds.toArray());
        return grouped;
    }

    private EchoSyncMetadataDto fetchSyncMetadata(long userId) {
        List<EchoSyncMetadataDto> rows = jdbc.query("""
                SELECT last_succeeded_at, history_starts_at, next_allowed_at
                FROM echo_sync_state
                WHERE user_id = ?
                """,
                (rs, rowNum) -> new EchoSyncMetadataDto(
                        toUtc(rs.getObject(1, OffsetDateTime.class)),
                        toUtc(rs.getObject(2, OffsetDateTime.class)),
                        toUtc(rs.getObject(3, OffsetDateTime.class))
                ),
                userId);
        return rows.isEmpty() ? new EchoSyncMetadataDto(null, null, null) : rows.getFirst();
    }

    private static OffsetDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.FeedDto;
import br.com.pablohcarmo.sonarfy.dto.ReviewDto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class FeedService {
    private final JdbcTemplate db;
    private final FeedCursorCodec cursors;
    public FeedService(JdbcTemplate db, FeedCursorCodec cursors) { this.db = db; this.cursors = cursors; }

    // Elegibilidade, relações, contagem e itens compartilham o snapshot da requisição.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedDto list(long actor, int limit, String cursor) {
        var eligible = db.query("SELECT active AND verified FROM users WHERE user_id = ?",
                (rs, n) -> rs.getBoolean(1), actor);
        if (eligible.isEmpty() || !eligible.getFirst()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (limit < 1 || limit > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be 1 to 50.");
        Long before = cursor == null ? null : cursors.decode(actor, cursor);
        long following = db.queryForObject("""
                SELECT count(*) FROM user_following f JOIN users u ON u.user_id = f.following_id
                WHERE f.follower_id = ? AND u.user_id <> ? AND u.active AND u.verified
                """, Long.class, actor, actor);
        var args = new java.util.ArrayList<Object>(java.util.List.of(actor, actor));
        if (before != null) args.add(before);
        args.add(limit + 1);
        var rows = db.query("""
                SELECT r.review_id, r.rating, r.review_text, r.created_at, r.updated_at,
                       u.user_id, u.handle, u.display_name, u.avatar,
                       a.spotify_id, a.name, a.cover, ar.spotify_id AS artist_spotify_id, ar.name AS artist_name
                FROM reviews r JOIN users u ON u.user_id = r.user_id
                JOIN albums a ON a.album_id = r.album_id JOIN artists ar ON ar.artist_id = a.artist_id
                WHERE u.active AND u.verified AND (r.user_id = ? OR EXISTS (
                    SELECT 1 FROM user_following f WHERE f.follower_id = ? AND f.following_id = r.user_id))
                """ + (before == null ? "" : " AND r.review_id < ?") + " ORDER BY r.review_id DESC LIMIT ?",
                (rs, n) -> new FeedDto.Item(new ReviewDto.Author(rs.getLong("user_id"), rs.getString("handle"),
                        rs.getString("display_name"), rs.getString("avatar")), rs.getLong("review_id"),
                        new ReviewDto.Album(rs.getString("spotify_id"), rs.getString("name"), rs.getString("cover"),
                                new ReviewDto.Artist(rs.getString("artist_spotify_id"), rs.getString("artist_name"))),
                        rs.getBigDecimal("rating"), rs.getString("review_text"),
                        rs.getObject("created_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC),
                        rs.getObject("updated_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)), args.toArray());
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return new FeedDto(items, following, more ? cursors.encode(actor, items.getLast().reviewId()) : null);
    }
}

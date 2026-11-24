package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.ReviewDto;
import br.com.pablohcarmo.sonarfy.dto.ReviewInputDto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class ReviewService {
    private final JdbcTemplate db;
    private static final String SELECT = """
            SELECT r.*, a.spotify_id, a.name, a.cover, ar.spotify_id AS artist_spotify_id, ar.name AS artist_name
            FROM reviews r JOIN albums a ON a.album_id = r.album_id
            JOIN artists ar ON ar.artist_id = a.artist_id
            """;

    public ReviewService(JdbcTemplate db) { this.db = db; }

    // Serializa mutações da conta e relê sua elegibilidade após aguardar o bloqueio.
    private void eligible(long actor, boolean lock) {
        var rows = db.query("SELECT active AND verified FROM users WHERE user_id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> rs.getBoolean(1), actor);
        if (rows.isEmpty() || !rows.getFirst()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @Transactional
    public ReviewDto create(long actor, String spotifyId, ReviewInputDto input) {
        var rating = input.validatedRating();
        String text = input.normalizedText();
        eligible(actor, true);
        var albums = db.query("SELECT album_id FROM albums WHERE spotify_id = ?", (rs, n) -> rs.getLong(1), spotifyId);
        if (albums.isEmpty()) throw missing();
        var ids = db.query("""
                INSERT INTO reviews (user_id, album_id, rating, review_text) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, album_id) DO NOTHING RETURNING review_id
                """, (rs, n) -> rs.getLong(1), actor, albums.getFirst(), rating, text);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Album already reviewed.");
        return find(actor, "r.review_id = ?", ids.getFirst());
    }

    @Transactional(readOnly = true)
    public ReviewDto current(long actor, String spotifyId) {
        eligible(actor, false);
        return find(actor, "a.spotify_id = ?", spotifyId);
    }

    // PUT substitui os campos editáveis: texto omitido ou null remove a resenha.
    @Transactional
    public ReviewDto replace(long actor, long reviewId, ReviewInputDto input) {
        var rating = input.validatedRating();
        String text = input.normalizedText();
        eligible(actor, true);
        if (db.update("UPDATE reviews SET rating = ?, review_text = ?, updated_at = CURRENT_TIMESTAMP WHERE review_id = ? AND user_id = ?",
                rating, text, reviewId, actor) == 0) throw missing();
        return find(actor, "r.review_id = ?", reviewId);
    }

    @Transactional
    public void delete(long actor, long reviewId) {
        eligible(actor, true);
        if (db.update("DELETE FROM reviews WHERE review_id = ? AND user_id = ?", reviewId, actor) == 0) throw missing();
    }

    // O ID imutável permite paginação estável mesmo quando a nota ou o texto mudam.
    @Transactional(readOnly = true)
    public ReviewDto.Page list(long actor, int limit, Long beforeId) {
        validatePage(limit, beforeId);
        eligible(actor, false);
        return page(actor, limit, beforeId);
    }

    // Identidade e avaliações compartilham o snapshot de elegibilidade do autor.
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReviewDto.ProfilePage profile(long actor, String handle, int limit, Long beforeId) {
        validatePage(limit, beforeId);
        eligible(actor, false);
        var authors = db.query("""
                SELECT user_id, handle, display_name, avatar FROM users
                WHERE lower(handle) = lower(?) AND active = true AND verified = true
                """, (rs, n) -> new ReviewDto.Author(rs.getLong("user_id"), rs.getString("handle"),
                rs.getString("display_name"), rs.getString("avatar")), handle);
        if (authors.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found.");
        var author = authors.getFirst();
        var page = page(author.id(), limit, beforeId);
        return new ReviewDto.ProfilePage(author, page.items(), page.nextBeforeId());
    }

    private static void validatePage(int limit, Long beforeId) {
        if (limit < 1 || limit > 50 || (beforeId != null && beforeId < 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be 1 to 50; beforeId must be positive.");
    }

    private ReviewDto.Page page(long actor, int limit, Long beforeId) {
        var rows = db.query(SELECT + " WHERE r.user_id = ? AND r.review_id < ? ORDER BY r.review_id DESC LIMIT ?",
                this::map, actor, beforeId == null ? Long.MAX_VALUE : beforeId, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return new ReviewDto.Page(items, more ? items.getLast().reviewId() : null);
    }

    private ReviewDto find(long actor, String predicate, Object value) {
        var rows = db.query(SELECT + " WHERE r.user_id = ? AND " + predicate, this::map, actor, value);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private ReviewDto map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ReviewDto(rs.getLong("review_id"), new ReviewDto.Album(rs.getString("spotify_id"),
                rs.getString("name"), rs.getString("cover"), new ReviewDto.Artist(rs.getString("artist_spotify_id"),
                rs.getString("artist_name"))), rs.getBigDecimal("rating"), rs.getString("review_text"),
                rs.getObject("created_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC),
                rs.getObject("updated_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Album or review not found.");
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AlbumDetailsService {
    private final AlbumCatalogService catalog;
    private final SpotifyCatalogTokenService tokens;
    private final JdbcTemplate db;
    private final TransactionTemplate snapshot;
    private final String market;
    public AlbumDetailsService(AlbumCatalogService catalog, SpotifyCatalogTokenService tokens,
            JdbcTemplate db, PlatformTransactionManager transactions, @Value("${SPOTIFY_CATALOG_MARKET:BR}") String market) {
        if (!market.matches("[A-Z]{2}")) throw new IllegalArgumentException("Invalid Spotify catalog market.");
        this.catalog = catalog; this.tokens = tokens; this.db = db; this.market = market;
        snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    // Revalida a conta antes do acesso externo e no snapshot que calcula os agregados.
    public AlbumDetailsDto get(long actor, String spotifyId) {
        if (spotifyId == null || !spotifyId.matches("[A-Za-z0-9]{22}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        snapshot.execute(s -> { eligible(actor); return null; });
        var used = new AtomicReference<String>();
        AlbumCatalogStore.Album album;
        try {
            album = catalog.resolve(spotifyId, () -> { var token = tokens.get(); used.set(token); return token; }, market);
        } catch (SpotifyApiException e) {
            if (e.getCategory() == SpotifyApiException.Category.UNAUTHORIZED) tokens.invalidate(used.get());
            throw e;
        }
        return snapshot.execute(s -> {
            eligible(actor);
            return db.queryForObject("""
                    SELECT round(avg(r.rating), 2) AS average, count(*) AS total FROM reviews r
                    JOIN users u ON u.user_id = r.user_id AND u.active AND u.verified WHERE r.album_id = ?
                    """, (rs, n) -> new AlbumDetailsDto(album.spotifyId(), album.name(), album.coverUrl(),
                    new ReviewDto.Artist(album.artistSpotifyId(), album.artistName()), album.releaseOn(),
                    album.releasePrecision(), album.totalTracks(), rs.getBigDecimal("average"), rs.getLong("total")), album.id());
        });
    }

    private void eligible(long actor) {
        var eligible = db.query("SELECT active AND verified FROM users WHERE user_id = ?", (rs, n) -> rs.getBoolean(1), actor);
        if (eligible.isEmpty() || !eligible.getFirst()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}

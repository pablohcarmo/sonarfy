package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyAlbumResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import static br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category.UNAVAILABLE;

@Service
public class AlbumCatalogStore {
    private final JdbcTemplate db;
    public AlbumCatalogStore(JdbcTemplate db) { this.db = db; }

    public record Album(long id, String spotifyId, String name, String coverUrl, String releaseOn,
                        String releasePrecision, Integer totalTracks, String artistSpotifyId, String artistName) {}

    // A leitura local não exige credencial Spotify e conserva a identidade de Ecos.
    @Transactional(readOnly = true)
    public Optional<Album> find(String spotifyId) {
        return db.query("""
                SELECT a.*, ar.spotify_id AS artist_spotify_id, ar.name AS artist_name
                FROM albums a JOIN artists ar ON ar.artist_id = a.artist_id WHERE a.spotify_id = ?
                """, (rs, n) -> new Album(rs.getLong("album_id"), rs.getString("spotify_id"),
                rs.getString("name"), rs.getString("cover"), rs.getString("release_on"),
                rs.getString("release_date_precision"), rs.getObject("total_tracks", Integer.class),
                rs.getString("artist_spotify_id"), rs.getString("artist_name")), spotifyId).stream().findFirst();
    }

    // Os upserts compartilham a transação; conflitos reutilizam IDs e preservam referências.
    @Transactional
    public Album save(SpotifyAlbumResponseDto album) {
        var artist = album.artists().getFirst();
        if (album.name().trim().length() > 500 || artist.name().trim().length() > 500
                || !artist.id().matches("[A-Za-z0-9]{1,64}")) throw new SpotifyApiException(UNAVAILABLE);
        var release = EchoCatalogMapper.mapReleaseDate(album.releaseDate(), album.releaseDatePrecision());
        var artistId = db.queryForObject("""
                INSERT INTO artists (spotify_id, name) VALUES (?, ?)
                ON CONFLICT (spotify_id) DO UPDATE SET name = EXCLUDED.name RETURNING artist_id
                """, Long.class, artist.id(), artist.name().trim());
        db.update("""
                INSERT INTO albums (spotify_id, artist_id, name, cover, release_on, release_date_precision, total_tracks)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (spotify_id) DO UPDATE SET artist_id = EXCLUDED.artist_id, name = EXCLUDED.name,
                    cover = COALESCE(EXCLUDED.cover, albums.cover),
                    release_on = COALESCE(EXCLUDED.release_on, albums.release_on),
                    release_date_precision = COALESCE(EXCLUDED.release_date_precision, albums.release_date_precision),
                    total_tracks = EXCLUDED.total_tracks
                """, album.id(), artistId, album.name().trim(), EchoCatalogMapper.selectCoverUrl(album.images()),
                release.releaseOn(), release.precision(), album.totalTracks());
        return find(album.id()).orElseThrow();
    }
}

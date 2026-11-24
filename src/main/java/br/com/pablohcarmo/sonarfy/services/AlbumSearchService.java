package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.dto.*;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlbumSearchService {
    private final SpotifyApiClient client;
    private final SpotifyCatalogTokenService tokens;
    private final JdbcTemplate db;
    private final String market;

    public AlbumSearchService(SpotifyApiClient client, SpotifyCatalogTokenService tokens,
            JdbcTemplate db, @Value("${SPOTIFY_CATALOG_MARKET:BR}") String market) {
        if (!market.matches("[A-Z]{2}")) throw new IllegalArgumentException("Invalid Spotify catalog market.");
        this.client = client; this.tokens = tokens; this.db = db; this.market = market;
    }

    // Valida entrada e conta antes de obter o token; busca nao grava catalogo nem escutas.
    public AlbumSearchPageDto search(long actor, String query, int limit, int offset) {
        String q = query == null ? "" : query.replaceAll("^[\\p{javaWhitespace}\\p{Z}]+|[\\p{javaWhitespace}\\p{Z}]+$", "");
        int length = q.codePointCount(0, q.length());
        if (length < 2 || length > 100 || q.indexOf(0) >= 0 || limit < 1 || limit > 10 || offset < 0 || offset > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var eligible = db.query("SELECT active AND verified FROM users WHERE user_id = ?", (rs, n) -> rs.getBoolean(1), actor);
        if (eligible.isEmpty() || !eligible.getFirst()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String used = tokens.get();
        try {
            var result = client.searchAlbums("Bearer " + used, q, market, limit, offset);
            if (result == null || result.albums() == null || result.albums().items() == null
                    || result.albums().items().size() > limit) throw incompatible();
            var items = result.albums().items().stream().map(this::map).toList();
            Integer next = result.albums().next() != null && !result.albums().next().isBlank()
                    && !items.isEmpty() && offset + limit <= 1000 ? offset + limit : null;
            return new AlbumSearchPageDto(items, limit, offset, next);
        } catch (FeignException e) {
            if (e.status() == 401) tokens.invalidate(used);
            if (e.status() >= 0 && e.status() < 500 && e.status() != 429) throw incompatible();
            throw SpotifyApiFailures.translateFeignException(e);
        } catch (SpotifyApiException | ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw incompatible();
        }
    }

    // Reutiliza as regras de capa e data do catalogo sem persistir os resultados.
    private AlbumSearchPageDto.Item map(SpotifyAlbumResponseDto album) {
        if (album == null || album.id() == null || !album.id().matches("[A-Za-z0-9]{22}")
                || !"album".equals(album.type()) || !validName(album.name())
                || album.artists() == null || album.artists().isEmpty()
                || album.artists().getFirst() == null) throw incompatible();
        var artist = album.artists().getFirst();
        if (artist.id() == null || !artist.id().matches("[A-Za-z0-9]{1,64}") || !validName(artist.name()))
            throw incompatible();
        var release = EchoCatalogMapper.mapReleaseDate(album.releaseDate(), album.releaseDatePrecision());
        return new AlbumSearchPageDto.Item(album.id(), album.name().trim(), EchoCatalogMapper.selectCoverUrl(album.images()),
                new ReviewDto.Artist(artist.id(), artist.name().trim()), release.releaseOn(), release.precision(),
                album.totalTracks() == null || album.totalTracks() < 0 ? null : album.totalTracks());
    }

    private boolean validName(String value) { return value != null && !value.isBlank() && value.trim().length() <= 500; }
    private ResponseStatusException incompatible() { return new ResponseStatusException(HttpStatus.BAD_GATEWAY); }
}

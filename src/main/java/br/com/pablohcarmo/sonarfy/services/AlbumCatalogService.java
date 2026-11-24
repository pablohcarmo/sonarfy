package br.com.pablohcarmo.sonarfy.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

@Service
public class AlbumCatalogService {
    private final AlbumCatalogStore store;
    private final SpotifyAlbumService spotify;
    public AlbumCatalogService(AlbumCatalogStore store, SpotifyAlbumService spotify) {
        this.store = store;
        this.spotify = spotify;
    }

    // Suspende transações do chamador durante HTTP; a credencial é obtida apenas no cache miss.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AlbumCatalogStore.Album resolve(String spotifyId, Supplier<String> accessToken, String market) {
        if (spotifyId == null || !spotifyId.matches("[A-Za-z0-9]{22}"))
            throw new IllegalArgumentException("Invalid Spotify album ID.");
        if (market != null && !market.matches("[A-Z]{2}"))
            throw new IllegalArgumentException("Market must use two uppercase letters.");
        var local = store.find(spotifyId);
        if (local.isPresent() && local.get().totalTracks() != null) return local.get();
        return store.save(spotify.fetch(accessToken.get(), spotifyId, market));
    }
}

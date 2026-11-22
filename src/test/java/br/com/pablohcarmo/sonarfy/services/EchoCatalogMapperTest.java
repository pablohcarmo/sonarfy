package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EchoCatalogMapperTest {

    private final OffsetDateTime playedAt = OffsetDateTime.parse("2026-11-21T22:00:00Z");

    @Test
    void normalizesValidTrackWithMultipleArtistsAndClosestHttpsCover() {
        var album = new AlbumObject("album1", "  Album Title  ", "2024-05", "month",
                List.of(
                        new ImageObject("http://insecure.example/cover.jpg", 300, 300),
                        new ImageObject("https://i.scdn.co/image/" + "a".repeat(240), 300, 300),
                        new ImageObject("https://i.scdn.co/image/640", 640, 640),
                        new ImageObject("https://i.scdn.co/image/320", 320, 320)
                ),
                List.of(new ArtistObject("comp1", "Various Artists")));
        var track = new TrackObject("track1", "  Song Name  ", 215000, true, false, "track",
                album, List.of(
                        new ArtistObject("art1", "Artist One"),
                        new ArtistObject("art1", "Artist One"),
                        new ArtistObject("art2", "Artist Two")
                ));

        var result = EchoCatalogMapper.normalize(new PlayHistoryItem(track, playedAt)).orElseThrow();

        assertEquals("track1", result.trackSpotifyId());
        assertEquals("Song Name", result.trackName());
        assertEquals(215000, result.durationMs());
        assertTrue(result.explicit());
        assertEquals("comp1", result.album().primaryArtistSpotifyId());
        assertEquals("Various Artists", result.album().primaryArtistName());
        assertEquals("https://i.scdn.co/image/320", result.album().coverUrl());
        assertEquals("2024-05", result.album().releaseOn());
        assertEquals("mes", result.album().releasePrecision());
        assertEquals(2, result.artists().size());
    }

    @Test
    void skipsLocalNonTrackMissingAlbumAndOversizedNames() {
        var validAlbum = new AlbumObject("album1", "Album", "2024", "year", List.of(),
                List.of(new ArtistObject("art1", "Artist")));

        assertTrue(EchoCatalogMapper.normalize(null).isEmpty());
        assertTrue(EchoCatalogMapper.normalize(new PlayHistoryItem(
                new TrackObject("track1", "Local", 1000, false, true, "track", validAlbum,
                        List.of(new ArtistObject("art1", "Artist"))), playedAt)).isEmpty());
        assertTrue(EchoCatalogMapper.normalize(new PlayHistoryItem(
                new TrackObject("ep1", "Podcast", 1000, false, false, "episode", validAlbum,
                        List.of(new ArtistObject("art1", "Artist"))), playedAt)).isEmpty());
        assertTrue(EchoCatalogMapper.normalize(new PlayHistoryItem(
                new TrackObject("bad-id!", "Song", 1000, false, false, "track", validAlbum,
                        List.of(new ArtistObject("art1", "Artist"))), playedAt)).isEmpty());
        assertTrue(EchoCatalogMapper.normalize(new PlayHistoryItem(
                new TrackObject("track1", "x".repeat(501), 1000, false, false, "track", validAlbum,
                        List.of(new ArtistObject("art1", "Artist"))), playedAt)).isEmpty());
        assertTrue(EchoCatalogMapper.normalize(new PlayHistoryItem(
                new TrackObject("track1", "Song", 1000, false, false, "track", null,
                        List.of(new ArtistObject("art1", "Artist"))), playedAt)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "2024, year, 2024, ano",
            "2024-05, month, 2024-05, mes",
            "2024-05-19, day, 2024-05-19, dia"
    })
    void mapsValidReleaseDates(String raw, String precision, String expectedDate, String expectedPrecision) {
        var mapped = EchoCatalogMapper.mapReleaseDate(raw, precision);
        assertEquals(expectedDate, mapped.releaseOn());
        assertEquals(expectedPrecision, mapped.precision());
    }

    @ParameterizedTest
    @CsvSource({
            "0000, year",
            "2024-13, month",
            "2024-05-32, day",
            "2024, unknown"
    })
    void nullsInvalidOrZeroReleaseDates(String raw, String precision) {
        var mapped = EchoCatalogMapper.mapReleaseDate(raw, precision);
        assertNull(mapped.releaseOn());
        assertNull(mapped.precision());
    }
}

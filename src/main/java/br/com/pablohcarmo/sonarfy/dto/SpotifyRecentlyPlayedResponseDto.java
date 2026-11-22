package br.com.pablohcarmo.sonarfy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.List;

// Mapeia a resposta paginada de GET /v1/me/player/recently-played do Spotify.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyRecentlyPlayedResponseDto(
        List<PlayHistoryItem> items,
        Cursors cursors,
        Integer limit
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cursors(String after, String before) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayHistoryItem(
            TrackObject track,
            OffsetDateTime playedAt
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrackObject(
            String id,
            String name,
            Integer durationMs,
            Boolean explicit,
            Boolean isLocal,
            String type,
            AlbumObject album,
            List<ArtistObject> artists
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlbumObject(
            String id,
            String name,
            String releaseDate,
            String releaseDatePrecision,
            List<ImageObject> images,
            List<ArtistObject> artists
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtistObject(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageObject(String url, Integer height, Integer width) {}
}

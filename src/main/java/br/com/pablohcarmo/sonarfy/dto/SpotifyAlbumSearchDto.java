package br.com.pablohcarmo.sonarfy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyAlbumSearchDto(Page albums) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(List<SpotifyAlbumResponseDto> items, String next) {}
}

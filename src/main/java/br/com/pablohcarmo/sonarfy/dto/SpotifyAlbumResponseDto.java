package br.com.pablohcarmo.sonarfy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import java.util.List;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.ArtistObject;
import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto.ImageObject;

// Apenas metadados necessários ao catálogo; totalTracks não depende da página de faixas.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyAlbumResponseDto(String id, String name, String type, String albumType,
        Integer totalTracks, String releaseDate, String releaseDatePrecision,
        List<ImageObject> images, List<ArtistObject> artists) {}

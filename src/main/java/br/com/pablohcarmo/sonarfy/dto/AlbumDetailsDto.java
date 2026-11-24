package br.com.pablohcarmo.sonarfy.dto;

import java.math.BigDecimal;

public record AlbumDetailsDto(String spotifyId, String name, String coverUrl, ReviewDto.Artist artist,
        String releaseOn, String releasePrecision, Integer totalTracks, BigDecimal averageRating, long reviewCount) {}

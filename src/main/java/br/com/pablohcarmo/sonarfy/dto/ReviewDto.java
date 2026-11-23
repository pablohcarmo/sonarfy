package br.com.pablohcarmo.sonarfy.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record ReviewDto(long reviewId, Album album, BigDecimal rating, String reviewText,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public record Album(String spotifyId, String name, String coverUrl, Artist artist) {}
    public record Artist(String spotifyId, String name) {}
    public record Page(List<ReviewDto> items, Long nextBeforeId) {}
}

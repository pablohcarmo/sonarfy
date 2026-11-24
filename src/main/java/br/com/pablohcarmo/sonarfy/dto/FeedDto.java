package br.com.pablohcarmo.sonarfy.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record FeedDto(List<Item> items, long followingCount, String nextCursor) {
    public record Item(ReviewDto.Author author, long reviewId, ReviewDto.Album album, BigDecimal rating,
                       String reviewText, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}

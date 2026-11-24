package br.com.pablohcarmo.sonarfy.dto;

import java.util.List;

public record AlbumSearchPageDto(List<Item> items, int limit, int offset, Integer nextOffset) {
    public record Item(String spotifyId, String name, String coverUrl, ReviewDto.Artist artist,
                       String releaseOn, String releasePrecision, Integer totalTracks) {}
}

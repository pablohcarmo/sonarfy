package br.com.pablohcarmo.sonarfy.dto;

import java.time.OffsetDateTime;

public record ProfileDto(Identity identity, Viewer viewer, Statistics statistics) {
    public record Identity(Long id, String handle, String displayName, String avatarUrl,
                           String coverUrl, String biography, Location location, OffsetDateTime joinedAt) {}
    public record Location(String city, String country) {}
    public record Viewer(boolean isSelf, boolean canEdit, boolean isFollowing) {}
    public record Statistics(long followers, long following) {}
}

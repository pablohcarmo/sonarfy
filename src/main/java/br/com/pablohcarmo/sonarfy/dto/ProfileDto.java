package br.com.pablohcarmo.sonarfy.dto;

import java.time.OffsetDateTime;

public record ProfileDto(Identity identity, Viewer viewer) {
    public record Identity(Long id, String handle, String displayName, String avatarUrl,
                           String coverUrl, String biography, Location location, OffsetDateTime joinedAt) {}
    public record Location(String city, String country) {}
    public record Viewer(boolean isSelf, boolean canEdit) {}
}

package br.com.pablohcarmo.sonarfy.dto;

public record SpotifyAuthorizationAttempt(
        Long userId,
        long authorizationVersion
) {
}

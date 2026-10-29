package br.com.pablohcarmo.sonarfy.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LoginSpotifyResponseDto(
        String accessToken,
        String tokenType,
        Long expiresIn,
        String refreshToken
) {
    @Override
    public String toString() {
        return "LoginSpotifyResponseDto[hidden tokens]";
    }
}
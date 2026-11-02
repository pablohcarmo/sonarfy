package br.com.pablohcarmo.sonarfy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyTokenResponseDto(
        String accessToken,
        String tokenType,
        Long expiresIn,
        String refreshToken,
        String scope
) {
    @Override
    public String toString() {
        return "SpotifyTokenResponseDto[hidden tokens]";
    }
}

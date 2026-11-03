package br.com.pablohcarmo.sonarfy.dto;

public record LoginResponseDto(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}

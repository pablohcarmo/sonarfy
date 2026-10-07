package br.com.pablohcarmo.sonarfy.dto;

public record RegisterResponseDto(
        String message,
        String email,
        String registrationToken,
        long expiresInSeconds
    ) {
}

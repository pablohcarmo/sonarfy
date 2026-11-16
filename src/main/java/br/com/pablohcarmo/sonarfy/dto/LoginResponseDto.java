package br.com.pablohcarmo.sonarfy.dto;

public record LoginResponseDto(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
    // O DTO é serializado no HTTP, mas seu token não deve aparecer em logs.
    @Override
    public String toString() { return "LoginResponseDto[accessToken=hidden, tokenType=" + tokenType
            + ", expiresInSeconds=" + expiresInSeconds + "]"; }
}

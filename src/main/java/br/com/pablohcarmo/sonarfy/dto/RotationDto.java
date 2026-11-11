package br.com.pablohcarmo.sonarfy.dto;

public record RotationDto(
        Long userId,
        String refreshToken
) {
    // Evita expor o segredo em logs ou depender de entidades JPA fora da transação.
    @Override
    public String toString() { return "RotationDto[userId=" + userId + ", refreshToken=hidden]"; }
}

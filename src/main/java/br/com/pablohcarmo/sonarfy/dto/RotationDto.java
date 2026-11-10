package br.com.pablohcarmo.sonarfy.dto;

import br.com.pablohcarmo.sonarfy.entities.User;

public record RotationDto(
        // Resultado da renovação: o dono do token e o novo token bruto a devolver ao cliente.
        User user,
        String refreshToken
) {
}

package br.com.pablohcarmo.sonarfy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePasswordDto(
        @NotBlank(message = "Token is required.")
        String token,

        // Opcional, DTO utilizada para alterar senha de usuários logados e não logados (recuperação de senha)
        String currentPassword,

        @NotBlank(message = "New password is required.")
        @Size(min = 8, message = "Password must be at least 8 characters long")
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmNewPassword,

        String email
) {
}

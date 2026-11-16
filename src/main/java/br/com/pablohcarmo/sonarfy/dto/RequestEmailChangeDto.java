package br.com.pablohcarmo.sonarfy.dto;

import jakarta.validation.constraints.*;

public record RequestEmailChangeDto(@NotBlank @Email @Size(max = 150) String newEmail,
                                    @NotBlank String currentPassword) {
    // Evita expor a senha se o DTO for incluído em diagnósticos.
    @Override public String toString() { return "RequestEmailChangeDto[redacted]"; }
}

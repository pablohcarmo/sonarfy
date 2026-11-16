package br.com.pablohcarmo.sonarfy.dto;

import jakarta.validation.constraints.*;

public record ConfirmEmailChangeDto(@NotBlank String token) {
    @Override public String toString() { return "ConfirmEmailChangeDto[redacted]"; }
}

package br.com.pablohcarmo.sonarfy.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EmailChangeRequestDto(
        UUID id,
        long userId,
        String newEmail,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime consumedAt
) {}

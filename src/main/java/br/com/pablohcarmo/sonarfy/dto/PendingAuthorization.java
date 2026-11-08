package br.com.pablohcarmo.sonarfy.dto;

import java.time.Instant;

public record PendingAuthorization(
        Long userId,
        long authorizationVersion,
        Instant expiresAt
) {
}

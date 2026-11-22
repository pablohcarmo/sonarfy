package br.com.pablohcarmo.sonarfy.dto;

import java.time.OffsetDateTime;

// Resumo retornado por POST /api/echoes/sync para o usuário autenticado.
public record EchoSyncResponseDto(
        int fetched,
        int imported,
        int alreadyKnown,
        int skipped,
        boolean complete,
        boolean possibleGap,
        OffsetDateTime newestPlayedAt,
        OffsetDateTime syncedAt
) {}

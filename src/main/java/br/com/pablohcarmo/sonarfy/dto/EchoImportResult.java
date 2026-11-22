package br.com.pablohcarmo.sonarfy.dto;

import java.time.OffsetDateTime;

// Resume os contadores produzidos pela gravação idempotente de um lote de escutas.
public record EchoImportResult(
        int imported,
        int alreadyKnown,
        int skipped,
        OffsetDateTime newestPlayedAt
) {}

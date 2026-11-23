package br.com.pablohcarmo.sonarfy.dto;

import java.time.OffsetDateTime;
import java.util.List;

// Resposta paginada de GET /api/echoes para o usuário autenticado.
public record EchoesPageResponseDto(
        List<EchoListenItemDto> items,
        String nextCursor,
        EchoSyncMetadataDto sync
) {
    public record EchoListenItemDto(
            Long id,
            OffsetDateTime playedAt,
            EchoTrackDto track,
            List<EchoArtistDto> artists,
            EchoAlbumDto album
    ) {}

    public record EchoTrackDto(
            String spotifyId,
            String name,
            Integer durationMs,
            boolean explicit
    ) {}

    public record EchoArtistDto(
            String spotifyId,
            String name
    ) {}

    public record EchoAlbumDto(
            String spotifyId,
            String name,
            String coverUrl
    ) {}

    public record EchoSyncMetadataDto(
            OffsetDateTime lastSucceededAt,
            OffsetDateTime historyStartsAt,
            OffsetDateTime nextAllowedAt
    ) {}
}

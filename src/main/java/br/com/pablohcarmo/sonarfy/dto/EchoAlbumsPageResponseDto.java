package br.com.pablohcarmo.sonarfy.dto;

import java.time.OffsetDateTime;
import java.util.List;
import br.com.pablohcarmo.sonarfy.dto.EchoesPageResponseDto.*;

// Página de álbuns distintos do histórico importado do usuário.
public record EchoAlbumsPageResponseDto(
        List<AlbumItem> items, String nextCursor, EchoSyncMetadataDto sync) {
    public record AlbumItem(EchoAlbumDto album, OffsetDateTime lastPlayedAt, long listenCount,
                            boolean rated, Long reviewId) {}
}

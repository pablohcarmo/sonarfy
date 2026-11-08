package br.com.pablohcarmo.sonarfy.dto;

import br.com.pablohcarmo.sonarfy.entities.SpotifyConnection;
import br.com.pablohcarmo.sonarfy.enums.SpotifyConnectionStatus;

import java.time.OffsetDateTime;

public record SpotifyConnectionResponseDto(
        SpotifyConnectionStatus status,
        OffsetDateTime connectedAt
) {
}

package br.com.pablohcarmo.sonarfy.client;

import br.com.pablohcarmo.sonarfy.dto.SpotifyRecentlyPlayedResponseDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "SpotifyApiClient",
        url = "${spotify.api-url:https://api.spotify.com}",
        configuration = SpotifyApiClientConfig.class
)
public interface SpotifyApiClient {

    // Envia apenas limit e before (nunca after junto) conforme contrato do Spotify.
    @GetMapping("/v1/me/player/recently-played")
    SpotifyRecentlyPlayedResponseDto getRecentlyPlayed(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam("limit") int limit,
            @RequestParam(value = "before", required = false) Long before
    );
}

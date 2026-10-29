package br.com.pablohcarmo.sonarfy.client;

import br.com.pablohcarmo.sonarfy.dto.LoginSpotifyResponseDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@FeignClient(
        name = "AuthSpotifyClient",
        url = "https://accounts.spotify.com/"
)

public interface AuthSpotifyClient {

    @PostMapping (
            value = "/api/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    LoginSpotifyResponseDto exchangeCode(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody Map<String, String> form
    );
}

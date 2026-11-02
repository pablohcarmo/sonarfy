package br.com.pablohcarmo.sonarfy.client;

import feign.Request;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

// Registrada somente pelo @FeignClient, sem configuração global por component scan.
public class SpotifyTokenClientConfig {

    @Bean
    public Request.Options spotifyRequestOptions() {
        return new Request.Options(5, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, true);
    }
}

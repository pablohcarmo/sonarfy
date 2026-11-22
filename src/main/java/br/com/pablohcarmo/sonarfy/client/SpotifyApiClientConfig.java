package br.com.pablohcarmo.sonarfy.client;

import feign.FeignException;
import feign.Request;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

// Registrada somente pelo @FeignClient da API Web do Spotify, sem scan global.
public class SpotifyApiClientConfig {

    @Bean
    public feign.Retryer spotifyApiRetryer() {
        return feign.Retryer.NEVER_RETRY;
    }

    @Bean
    public ErrorDecoder spotifyApiErrorDecoder() {
        // Preserva status e cabeçalhos (ex.: Retry-After em 429) sem converter para RetryableException.
        return FeignException::errorStatus;
    }

    @Bean
    public Request.Options spotifyApiRequestOptions() {
        return new Request.Options(5, TimeUnit.SECONDS, 10, TimeUnit.SECONDS, true);
    }
}

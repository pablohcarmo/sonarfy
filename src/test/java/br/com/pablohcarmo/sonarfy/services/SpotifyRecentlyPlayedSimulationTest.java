package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

// Cliente Feign real contra a simulação Postman de GET /v1/me/player/recently-played.
@EnabledIfSystemProperty(named = "sonarfy.test.spotify-api-sim", matches = "true")
@SpringJUnitConfig(SpotifyRecentlyPlayedSimulationTest.Config.class)
class SpotifyRecentlyPlayedSimulationTest {

    @Configuration
    @EnableFeignClients(clients = SpotifyApiClient.class)
    @ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @Import(SpotifyRecentlyPlayedService.class)
    static class Config {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spotify.api-url", () -> System.getProperty(
                "sonarfy.test.spotify-api-url", "http://127.0.0.1:55442/spotify-api"));
    }

    @Autowired
    SpotifyRecentlyPlayedService service;

    @Test
    void fetchesPaginatedHistoryAndTranslatesProviderFailures() {
        var first = service.fetchPage("token-ok", 50, null);
        assertEquals(1, first.items().size());
        assertEquals("track1", first.items().getFirst().track().id());
        assertEquals("Track One", first.items().getFirst().track().name());
        assertEquals(2, first.items().getFirst().track().artists().size());
        assertEquals("day", first.items().getFirst().track().album().releaseDatePrecision());
        assertEquals(OffsetDateTime.parse("2026-11-21T20:10:00Z"), first.items().getFirst().playedAt());

        long beforeCursor = Long.parseLong(first.cursors().before());
        var second = service.fetchPage("token-ok", 50, beforeCursor);
        assertEquals(1, second.items().size());
        assertEquals("track0", second.items().getFirst().track().id());

        assertEquals(Category.UNAUTHORIZED,
                assertThrows(SpotifyApiException.class, () -> service.fetchPage("token-401", 50, null)).getCategory());
        assertEquals(Category.FORBIDDEN,
                assertThrows(SpotifyApiException.class, () -> service.fetchPage("token-403", 50, null)).getCategory());

        var rateLimited = assertThrows(SpotifyApiException.class, () -> service.fetchPage("token-429", 50, null));
        assertEquals(Category.RATE_LIMITED, rateLimited.getCategory());
        assertEquals(45L, rateLimited.getRetryAfterSeconds());

        var longerDelay = assertThrows(SpotifyApiException.class, () -> service.fetchPage("token-429-large", 50, null));
        assertEquals(900L, longerDelay.getRetryAfterSeconds());

        assertEquals(Category.UNAVAILABLE,
                assertThrows(SpotifyApiException.class, () -> service.fetchPage("token-503", 50, null)).getCategory());
        assertEquals(Category.UNAVAILABLE,
                assertThrows(SpotifyApiException.class, () -> service.fetchPage("token-malformed", 50, null)).getCategory());
        assertEquals(Category.TIMEOUT,
                assertThrows(SpotifyApiException.class, () -> service.fetchPage("token-timeout", 50, null)).getCategory());
    }
}

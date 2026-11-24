package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

// Reutiliza a configuração Feign real e a simulação versionada da API Spotify.
@EnabledIfSystemProperty(named = "sonarfy.test.spotify-api-sim", matches = "true")
@SpringJUnitConfig(SpotifyRecentlyPlayedSimulationTest.Config.class)
@Import(SpotifyAlbumService.class)
class SpotifyAlbumSimulationTest {
    private static final String ID = "4aawyAB9vmqN3uQ7FjRGTy";
    @Autowired SpotifyAlbumService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spotify.api-url", () -> System.getProperty(
                "sonarfy.test.spotify-api-url", "http://127.0.0.1:55442/spotify-api"));
    }

    // O total oficial deve sobreviver mesmo com uma página de faixas incompleta.
    @Test
    void readsMetadataWithAndWithoutMarket() {
        for (String market : new String[]{null, "BR"}) {
            var album = service.fetch("token-ok", ID, market);
            assertEquals(ID, album.id());
            assertEquals("Album One", album.name());
            assertEquals(70, album.totalTracks());
            assertEquals("2024-05", album.releaseDate());
            assertEquals("month", album.releaseDatePrecision());
            assertEquals("Artist One", album.artists().getFirst().name());
            assertTrue(album.images().isEmpty());
        }
    }

    // Falhas HTTP são distintas, sanitizadas e preservam o prazo de rate limit.
    @Test
    void translatesFailuresWithoutLeakingProviderBody() {
        String[] tokens = {"401", "403", "404", "429", "500", "503", "malformed", "invalid", "mismatch", "null", "timeout"};
        Category[] categories = {Category.UNAUTHORIZED, Category.FORBIDDEN, Category.NOT_FOUND,
                Category.RATE_LIMITED, Category.UNAVAILABLE, Category.UNAVAILABLE, Category.UNAVAILABLE,
                Category.UNAVAILABLE, Category.UNAVAILABLE, Category.UNAVAILABLE, Category.TIMEOUT};
        for (int i = 0; i < tokens.length; i++) {
            String token = "token-" + tokens[i];
            var error = assertThrows(SpotifyApiException.class, () -> service.fetch(token, ID, "BR"));
            assertEquals(categories[i], error.getCategory(), token);
            assertNull(error.getCause());
            assertFalse(error.toString().contains(token));
            assertFalse(error.toString().contains("sensitive-provider-body"));
            if (categories[i] == Category.RATE_LIMITED) assertEquals(900L, error.getRetryAfterSeconds());
        }
    }
}

package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyApiClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotifyAlbumServiceTest {
    // Entradas inválidas não alcançam o transporte nem permitem injeção no caminho.
    @Test
    void rejectsInvalidInputsBeforeCallingSpotify() {
        var client = mock(SpotifyApiClient.class);
        var service = new SpotifyAlbumService(client);
        String id = "4aawyAB9vmqN3uQ7FjRGTy";
        for (String token : new String[]{null, "", " "})
            assertThrows(IllegalArgumentException.class, () -> service.fetch(token, id, "BR"));
        for (String invalid : new String[]{null, "", "../albums", "spotify:album:" + id, id + "?x=1"})
            assertThrows(IllegalArgumentException.class, () -> service.fetch("token", invalid, "BR"));
        for (String market : new String[]{"", "br", "BRA", "12"})
            assertThrows(IllegalArgumentException.class, () -> service.fetch("token", id, market));
        verifyNoInteractions(client);
    }
}

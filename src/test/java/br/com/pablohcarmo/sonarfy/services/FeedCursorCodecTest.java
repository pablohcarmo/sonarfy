package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class FeedCursorCodecTest {
    // Versão, identidade e fronteira assinadas resistem a alterações, inclusive com Base64 válido.
    @Test void signedContextAndVersion() {
        var codec = new FeedCursorCodec("test-secret");
        String token = codec.encode(7, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, codec.decode(7, token));
        assertThrows(ResponseStatusException.class, () -> codec.decode(8, token));
        String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        for (String changed : new String[]{raw.replace("v1:", "v2:"), raw.replace(":7:", ":8:"), raw.replace("9223372036854775807", "1")}) {
            String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(StandardCharsets.UTF_8));
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> codec.decode(7, tampered)).getStatusCode().value());
        }
        for (long id : new long[]{0, -1}) assertThrows(ResponseStatusException.class, () -> codec.decode(7, codec.encode(7, id)));
    }
}

package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EchoCursorCodecTest {

    private final EchoCursorCodec codec = new EchoCursorCodec("cursor-test-secret");

    @Test
    void roundTripsMicrosecondPrecisionForSameUser() {
        var playedAt = OffsetDateTime.parse("2026-11-22T20:15:30.123456Z");
        String cursor = codec.encode(42L, playedAt, 987L);

        var decoded = codec.decode(42L, cursor);

        assertEquals(playedAt.toInstant(), decoded.playedAt().toInstant());
        assertEquals(987L, decoded.listenId());
    }

    @Test
    void rejectsCursorFromAnotherUser() {
        String cursor = codec.encode(42L, OffsetDateTime.parse("2026-11-22T20:15:30Z"), 10L);

        var ex = assertThrows(ResponseStatusException.class, () -> codec.decode(99L, cursor));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void rejectsTamperedVersionAndSignature() {
        String cursor = codec.encode(42L, OffsetDateTime.parse("2026-11-22T20:15:30Z"), 10L);
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String tamperedVersion = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.replaceFirst("^v1\\.", "v2.").getBytes(StandardCharsets.UTF_8));
        String tamperedSig = cursor.substring(0, cursor.length() - 2) + "AA";

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> codec.decode(42L, tamperedVersion)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> codec.decode(42L, tamperedSig)).getStatusCode());
    }

    // Rejeita cursores de outra consulta ou de outro álbum, preservando o cursor legado.
    @Test
    void isolatesQueryAndAlbumScopes() {
        var time = OffsetDateTime.parse("2026-11-23T12:00:00Z");
        var albums = codec.scoped("albums");
        String cursor = albums.encode(42L, time, 10L);
        assertEquals(10L, codec.scoped("albums").decode(42L, cursor).listenId());
        assertThrows(ResponseStatusException.class, () -> codec.decode(42L, cursor));
        assertThrows(ResponseStatusException.class, () -> albums.decode(99L, cursor));
        assertThrows(ResponseStatusException.class,
                () -> albums.decode(42L, codec.encode(42L, time, 10L)));
        String history = codec.scoped("album:first").encode(42L, time, 10L);
        assertThrows(ResponseStatusException.class, () -> codec.scoped("album:second").decode(42L, history));
        assertThrows(ResponseStatusException.class, () -> albums.decode(42L, history));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-base64!", "bm90LWEtY3Vyc29y"})
    void rejectsMalformedCursors(String invalid) {
        var ex = assertThrows(ResponseStatusException.class, () -> codec.decode(42L, invalid));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}

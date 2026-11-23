package br.com.pablohcarmo.sonarfy.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

@Service
public class EchoCursorCodec {

    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] signingKey;

    public EchoCursorCodec(@Value("${api.security.token.secret}") String tokenSecret) {
        if (tokenSecret == null || tokenSecret.isBlank()) {
            throw new IllegalArgumentException("Cursor signing secret is required.");
        }
        try {
            // Deriva chave própria por domínio para não reutilizar tokens JWT como cursor.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            this.signingKey = digest.digest(("sonarfy-echo-cursor-v1:" + tokenSecret).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to initialize cursor signing key.", exception);
        }
    }

    public record DecodedCursor(OffsetDateTime playedAt, long listenId) {}

    // Codifica posição keyset com precisão de nanossegundos, versão e vínculo ao usuário.
    public String encode(long userId, OffsetDateTime playedAt, long listenId) {
        if (playedAt == null || listenId <= 0L) {
            throw new IllegalArgumentException("Valid cursor position is required.");
        }
        Instant instant = playedAt.toInstant();
        String payload = VERSION + "." + userId + "." + instant.getEpochSecond() + "." + instant.getNano() + "." + listenId;
        String signature = sign(payload);
        return ENCODER.encodeToString((payload + "." + signature).getBytes(StandardCharsets.UTF_8));
    }

    // Valida assinatura, versão e pertencimento ao usuário autenticado.
    public DecodedCursor decode(long expectedUserId, String opaqueCursor) {
        if (opaqueCursor == null || opaqueCursor.isBlank() || opaqueCursor.length() > 512) {
            throw invalidCursor();
        }
        final String decoded;
        try {
            decoded = new String(DECODER.decode(opaqueCursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
        String[] parts = decoded.split("\\.");
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            throw invalidCursor();
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3] + "." + parts[4];
        String expectedSig = sign(payload);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[5].getBytes(StandardCharsets.UTF_8))) {
            throw invalidCursor();
        }
        try {
            long userId = Long.parseLong(parts[1]);
            if (userId != expectedUserId) {
                throw invalidCursor();
            }
            long epochSeconds = Long.parseLong(parts[2]);
            int nanos = Integer.parseInt(parts[3]);
            long listenId = Long.parseLong(parts[4]);
            if (nanos < 0 || nanos >= 1_000_000_000 || listenId <= 0L) {
                throw invalidCursor();
            }
            OffsetDateTime playedAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds, nanos), ZoneOffset.UTC);
            return new DecodedCursor(playedAt, listenId);
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign cursor.", exception);
        }
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor.");
    }
}

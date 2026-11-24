package br.com.pablohcarmo.sonarfy.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class CollectionCursorCodec {
    private final byte[] key;
    public CollectionCursorCodec(@Value("${api.security.token.secret}") String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Cursor secret required.");
        key = ("sonarfy-collection-v1:" + secret).getBytes(StandardCharsets.UTF_8);
    }
    public record Position(long id, int halfPoints) {}

    // Assina também o contexto, impedindo reutilização em outro perfil, visitante ou consulta.
    public String encode(String context, Position position) {
        String payload = "v1:" + position.id() + ":" + position.halfPoints();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + ":" + sign(context + ":" + payload)).getBytes(StandardCharsets.UTF_8));
    }

    // A fronteira continua válida após exclusão ou edição da avaliação original.
    public Position decode(String context, String cursor) {
        try {
            if (cursor.isBlank() || cursor.length() > 512) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 4 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            String payload = String.join(":", parts[0], parts[1], parts[2]);
            if (!MessageDigest.isEqual(sign(context + ":" + payload).getBytes(StandardCharsets.UTF_8),
                    parts[3].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            var position = new Position(Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
            if (position.id() < 1 || position.halfPoints() < 0 || position.halfPoints() > 20)
                throw new IllegalArgumentException();
            return position;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid collection cursor.");
        }
    }

    private String sign(String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign cursor.", e);
        }
    }
}

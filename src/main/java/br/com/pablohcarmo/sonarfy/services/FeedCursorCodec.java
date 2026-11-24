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
public class FeedCursorCodec {
    private final byte[] key;
    public FeedCursorCodec(@Value("${api.security.token.secret}") String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Cursor secret required.");
        key = ("sonarfy-feed-v1:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    // O domínio da chave e a identidade assinada isolam a consulta do feed.
    public String encode(long actor, long id) {
        String payload = "v1:" + actor + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + ":" + sign(payload)).getBytes(StandardCharsets.UTF_8));
    }

    // A fronteira imutável não depende da existência da avaliação original.
    public long decode(long actor, String cursor) {
        try {
            if (cursor.isBlank() || cursor.length() > 512) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 4 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            String payload = String.join(":", parts[0], parts[1], parts[2]);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                    parts[3].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            long id = Long.parseLong(parts[2]);
            if (Long.parseLong(parts[1]) != actor || id < 1) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid feed cursor.");
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

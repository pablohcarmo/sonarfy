package br.com.pablohcarmo.sonarfy.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SpotifyTokenEncryptionService {
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SpotifyTokenEncryptionService(@Value("${spotify_token_encryption_key:}") String encodedKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("spotify_token_encryption_key must be a Base64 encoded 32-byte key.");
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException("spotify_token_encryption_key must be a Base64 encoded 32-byte key.");
        }
        key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String token, Long userId, String tokenKind) {
        // Gera um IV novo e retorna o IV junto ao conteúdo cifrado para permitir a descriptografia.
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, userId, tokenKind);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot encrypt Spotify token.");
        }
    }

    public String decrypt(String encrypted, Long userId, String tokenKind) {
        // Valida o formato, separa o IV do conteúdo cifrado e autentica antes de devolver o token.
        try {
            if (encrypted == null || !encrypted.startsWith(PREFIX)) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            if (bytes.length < IV_LENGTH + 16) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            return new String(cipher(Cipher.DECRYPT_MODE, iv, userId, tokenKind).doFinal(ciphertext),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot decrypt Spotify token.");
        }
    }

    private Cipher cipher(int mode, byte[] iv, Long userId, String tokenKind) throws GeneralSecurityException {
        // Configura AES-GCM e vincula o valor ao usuário e à finalidade informados.
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        // Autentica o formato, o usuário e o tipo do token para impedir a troca dos valores armazenados.
        cipher.updateAAD(("spotify:" + PREFIX + userId + ":" + tokenKind).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}

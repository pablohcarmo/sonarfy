package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyTokenEncryptionServiceTest {
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final SpotifyTokenEncryptionService encryption = new SpotifyTokenEncryptionService(TEST_KEY);

    @Test
    void encryptsWithFreshIvAndDecryptsForSameOwnerAndPurpose() {
        String token = "private-test-token";
        String encrypted1 = encryption.encrypt(token, 7L, "access");
        String encrypted2 = encryption.encrypt(token, 7L, "access");
        assertNotEquals(encrypted1, encrypted2);
        assertFalse(encrypted1.contains(token));
        assertEquals(token, encryption.decrypt(encrypted1, 7L, "access"));
    }

    @Test
    void rejectsDifferentOwnerPurposeAndTamperedCiphertext() {
        String encrypted = encryption.encrypt("secret", 7L, "access");
        assertThrows(IllegalStateException.class, () -> encryption.decrypt(encrypted, 8L, "access"));
        assertThrows(IllegalStateException.class, () -> encryption.decrypt(encrypted, 7L, "refresh"));
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");
        assertThrows(IllegalStateException.class, () -> encryption.decrypt(tampered, 7L, "access"));
    }

    @Test
    void rejectsKeysThatAreNotBase64Encoded256BitKeys() {
        assertThrows(IllegalArgumentException.class, () -> new SpotifyTokenEncryptionService("invalid"));
        assertThrows(IllegalArgumentException.class, () -> new SpotifyTokenEncryptionService(
                Base64.getEncoder().encodeToString(new byte[16])));
    }
}

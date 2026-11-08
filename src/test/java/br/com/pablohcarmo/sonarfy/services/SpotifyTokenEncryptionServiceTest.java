package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.security.SecureRandom;
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

    @Test
    void readsLegacyValuesAndWritesWithTheConfiguredActiveKey() {
        String secondKey = Base64.getEncoder().encodeToString(new byte[32]);
        String thirdKey = Base64.getEncoder().encodeToString(new byte[]{
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
        });
        var legacy = new SpotifyTokenEncryptionService(secondKey);
        var rotating = new SpotifyTokenEncryptionService(
                secondKey,
                "legacy=" + secondKey + ",next=" + thirdKey,
                "next",
                new SecureRandom()
        );

        String legacyCiphertext = legacy.encrypt("legacy-token", 7L, "access");
        String currentCiphertext = rotating.encrypt("new-token", 7L, "refresh");

        assertTrue(currentCiphertext.startsWith("v2:next:"));
        assertEquals("legacy-token", rotating.decrypt(legacyCiphertext, 7L, "access"));
        assertEquals("new-token", rotating.decrypt(currentCiphertext, 7L, "refresh"));
        assertNotEquals(legacyCiphertext, rotating.reencrypt(legacyCiphertext, 7L, "access"));
        assertTrue(rotating.reencrypt(legacyCiphertext, 7L, "access").startsWith("v2:next:"));
    }

    @Test
    void selectsTheKeyFromTheCiphertextAndRejectsUnknownKeyIds() {
        String keyA = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] bytesB = new byte[32];
        java.util.Arrays.fill(bytesB, (byte) 2);
        String keyB = Base64.getEncoder().encodeToString(bytesB);
        var keyAWriter = new SpotifyTokenEncryptionService(keyA, "a=" + keyA, "a", new SecureRandom());
        var keyBReader = new SpotifyTokenEncryptionService(
                keyA,
                "legacy=" + keyA + ",a=" + keyA + ",b=" + keyB,
                "b",
                new SecureRandom()
        );
        String ciphertext = keyAWriter.encrypt("key-a-token", 3L, "refresh");

        assertTrue(ciphertext.startsWith("v2:a:"));
        assertEquals("key-a-token", keyBReader.decrypt(ciphertext, 3L, "refresh"));
        assertThrows(IllegalStateException.class,
                () -> keyBReader.decrypt(ciphertext.replace("v2:a:", "v2:missing:"), 3L, "refresh"));
        assertThrows(IllegalArgumentException.class,
                () -> new SpotifyTokenEncryptionService(keyA, "a=" + keyA, "missing", new SecureRandom()));
    }

    @Test
    void rejectsDuplicateKeyIdsAndConflictingLegacyKeys() {
        String keyA = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] bytesB = new byte[32];
        java.util.Arrays.fill(bytesB, (byte) 3);
        String keyB = Base64.getEncoder().encodeToString(bytesB);

        assertThrows(IllegalArgumentException.class,
                () -> new SpotifyTokenEncryptionService(keyA, "same=" + keyA + ",same=" + keyB,
                        "same", new SecureRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> new SpotifyTokenEncryptionService(keyA, "bad_id=" + keyA, "bad_id", new SecureRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> new SpotifyTokenEncryptionService(keyA, "legacy=" + keyB, "", new SecureRandom()));
    }
}

package br.com.pablohcarmo.sonarfy.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SpotifyTokenEncryptionService {
    private static final String LEGACY_PREFIX = "v1:";
    private static final String ROTATABLE_PREFIX = "v2:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;
    private static final Pattern VALID_KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final SecretKeySpec legacyKey;
    private final Map<String, SecretKeySpec> keyring;
    private final String activeKeyId;
    private final SecureRandom random;

    // Carrega a chave legada, o chaveiro e o ID usado para novas cifras.
    @Autowired
    public SpotifyTokenEncryptionService(
            @Value("${spotify_token_encryption_key:}") String legacyEncodedKey,
            @Value("${spotify_token_encryption_keyring:}") String encodedKeyring,
            @Value("${spotify_token_encryption_active_key_id:}") String activeKeyId
    ) {
        this(legacyEncodedKey, encodedKeyring, activeKeyId, new SecureRandom());
    }

    // Mantém o construtor simples usado nos testes do formato legado.
    SpotifyTokenEncryptionService(String encodedKey) {
        this(encodedKey, "", "", new SecureRandom());
    }

    SpotifyTokenEncryptionService(
            String legacyEncodedKey,
            String encodedKeyring,
            String activeKeyId,
            SecureRandom random
    ) {
        // Valida e combina as chaves antes de disponibilizar operações de cifra.
        Map<String, SecretKeySpec> keys = parseKeyring(encodedKeyring);
        SecretKeySpec configuredLegacyKey = decodeKeyIfPresent(legacyEncodedKey);
        SecretKeySpec keyringLegacyKey = keys.get("legacy");
        if (configuredLegacyKey != null && keyringLegacyKey != null
                && !Arrays.equals(configuredLegacyKey.getEncoded(), keyringLegacyKey.getEncoded())) {
            throw new IllegalArgumentException("Legacy Spotify encryption keys do not match.");
        }

        this.legacyKey = configuredLegacyKey != null ? configuredLegacyKey : keyringLegacyKey;
        if (legacyKey == null) {
            throw new IllegalArgumentException("A legacy Spotify token encryption key is required.");
        }
        keys.putIfAbsent("legacy", legacyKey);

        if (activeKeyId == null || activeKeyId.isBlank()) {
            this.activeKeyId = null;
        } else {
            validateKeyId(activeKeyId);
            if (!keys.containsKey(activeKeyId)) {
                throw new IllegalArgumentException("The active Spotify encryption key is not present in the keyring.");
            }
            this.activeKeyId = activeKeyId;
        }

        this.keyring = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        this.random = random;
    }

    // Cifra com a chave ativa; sem ID ativo, mantém o formato legado v1.
    public String encrypt(String token, Long userId, String tokenKind) {
        String format = activeKeyId == null ? "v1" : "v2";
        String keyId = activeKeyId;
        SecretKeySpec key = keyId == null ? legacyKey : keyring.get(keyId);
        String prefix = keyId == null ? LEGACY_PREFIX : ROTATABLE_PREFIX + keyId + ":";
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, key, format, keyId, userId, tokenKind);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return prefix + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot encrypt Spotify token.");
        }
    }

    // Identifica o formato e a chave pelo envelope, autentica o conteúdo e devolve o token.
    public String decrypt(String encrypted, Long userId, String tokenKind) {
        try {
            ParsedCiphertext parsed = parseCiphertext(encrypted);
            ByteBuffer buffer = ByteBuffer.wrap(parsed.payload());
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv, parsed.key(), parsed.format(),
                    parsed.keyId(), userId, tokenKind);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot decrypt Spotify token.");
        }
    }

    // Descriptografa o valor atual e o cifra novamente com a configuração ativa.
    public String reencrypt(String encrypted, Long userId, String tokenKind) {
        return encrypt(decrypt(encrypted, userId, tokenKind), userId, tokenKind);
    }

    // Retorna o padrão usado para localizar tokens que ainda precisam de recriptografia.
    public String activeCiphertextPattern() {
        return activeKeyId == null ? LEGACY_PREFIX + "%" : ROTATABLE_PREFIX + activeKeyId + ":%";
    }

    // Retorna o identificador lógico da chave que recebe novas cifras.
    public String activeKeyUsageId() {
        return activeKeyId == null ? "v1:legacy" : "v2:" + activeKeyId;
    }

    // Lista os IDs de chave disponíveis para descriptografar tokens armazenados.
    public Set<String> keyIds() {
        return keyring.keySet();
    }

    // Lê o prefixo do envelope e seleciona a chave correspondente para descriptografia.
    private ParsedCiphertext parseCiphertext(String encrypted) {
        if (encrypted == null) {
            throw new IllegalArgumentException();
        }
        if (encrypted.startsWith(LEGACY_PREFIX)) {
            return new ParsedCiphertext("v1", null, legacyKey,
                    decodePayload(encrypted.substring(LEGACY_PREFIX.length())));
        }
        if (!encrypted.startsWith(ROTATABLE_PREFIX)) {
            throw new IllegalArgumentException();
        }

        int keyIdEnd = encrypted.indexOf(':', ROTATABLE_PREFIX.length());
        if (keyIdEnd < 0) {
            throw new IllegalArgumentException();
        }
        String keyId = encrypted.substring(ROTATABLE_PREFIX.length(), keyIdEnd);
        validateKeyId(keyId);
        SecretKeySpec key = keyring.get(keyId);
        if (key == null) {
            throw new IllegalArgumentException();
        }
        return new ParsedCiphertext("v2", keyId, key, decodePayload(encrypted.substring(keyIdEnd + 1)));
    }

    // Decodifica o payload e verifica se há espaço para IV e tag de autenticação.
    private static byte[] decodePayload(String encodedPayload) {
        byte[] payload = Base64.getDecoder().decode(encodedPayload);
        if (payload.length < IV_LENGTH + TAG_LENGTH_BYTES) {
            throw new IllegalArgumentException();
        }
        return payload;
    }

    // Converte as entradas `id=chave` em um chaveiro validado.
    private static Map<String, SecretKeySpec> parseKeyring(String encodedKeyring) {
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        if (encodedKeyring == null || encodedKeyring.isBlank()) {
            return keys;
        }
        for (String entry : encodedKeyring.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("Spotify encryption keyring must contain key-id=Base64-key entries.");
            }
            String keyId = entry.substring(0, separator).trim();
            validateKeyId(keyId);
            SecretKeySpec key = decodeKey(entry.substring(separator + 1).trim());
            if (keys.putIfAbsent(keyId, key) != null) {
                throw new IllegalArgumentException("Spotify encryption keyring contains a duplicate key ID.");
            }
        }
        return keys;
    }

    // Decodifica a chave somente quando a configuração não está vazia.
    private static SecretKeySpec decodeKeyIfPresent(String encodedKey) {
        return encodedKey == null || encodedKey.isBlank() ? null : decodeKey(encodedKey);
    }

    // Valida e converte uma chave Base64 de 32 bytes para AES.
    private static SecretKeySpec decodeKey(String encodedKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Spotify token encryption keys must be Base64 encoded 32-byte keys.");
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException("Spotify token encryption keys must be Base64 encoded 32-byte keys.");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    // Restringe o ID da chave aos caracteres aceitos pelo formato do envelope.
    private static void validateKeyId(String keyId) {
        if (keyId == null || !VALID_KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Spotify encryption key IDs must use 1 to 64 letters, digits, '.', '_' or '-'.");
        }
    }

    // Configura AES-GCM e vincula formato, chave, usuário e finalidade via AAD.
    private static Cipher cipher(
            int mode,
            byte[] iv,
            SecretKeySpec key,
            String format,
            String keyId,
            Long userId,
            String tokenKind
    ) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        String aad = keyId == null
                ? "spotify:" + format + ":" + userId + ":" + tokenKind
                : "spotify:" + format + ":" + keyId + ":" + userId + ":" + tokenKind;
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private record ParsedCiphertext(String format, String keyId, SecretKeySpec key, byte[] payload) {
    }
}

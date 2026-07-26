package io.testhub.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-GCM key ring. v1 payloads remain readable during the staged migration to keyed v2 payloads. */
@Component
public class SecretCipher {
    private static final String LEGACY_VERSION = "v1";
    private static final String VERSION = "v2";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;
    private final String activeKeyId;
    private final SecretKeySpec activeKey;
    private final Map<String, SecretKeySpec> decryptKeys;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${testhub.security.encryption-key}") String encodedKey,
                        @Value("${testhub.security.encryption-key-id:primary}") String keyId,
                        @Value("${testhub.security.previous-encryption-keys:}") String previousKeys) {
        this.activeKeyId = requireKeyId(keyId);
        this.activeKey = decodeKey(encodedKey, "TESTHUB_ENCRYPTION_KEY");
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        keys.put(activeKeyId, activeKey);
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String entry : previousKeys.split(";")) {
                if (entry.isBlank()) continue;
                int delimiter = entry.indexOf(':');
                if (delimiter <= 0 || delimiter == entry.length() - 1) {
                    throw new IllegalStateException("TESTHUB_PREVIOUS_ENCRYPTION_KEYS 必须使用 keyId:Base64Key;... 格式");
                }
                String previousKeyId = requireKeyId(entry.substring(0, delimiter));
                if (keys.containsKey(previousKeyId)) {
                    throw new IllegalStateException("加密密钥标识不能重复：" + previousKeyId);
                }
                keys.put(previousKeyId, decodeKey(entry.substring(delimiter + 1), "TESTHUB_PREVIOUS_ENCRYPTION_KEYS"));
            }
        }
        this.decryptKeys = Map.copyOf(keys);
    }

    public String encrypt(String plainText) {
        return VERSION + ":" + activeKeyId + ":" + encryptPayload(plainText, activeKey);
    }

    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) throw failure();
        if (encryptedValue.startsWith(VERSION + ":")) return decryptV2(encryptedValue);
        if (encryptedValue.startsWith(LEGACY_VERSION + ":")) return decryptLegacy(encryptedValue);
        throw failure();
    }

    public String activeKeyId() { return activeKeyId; }

    public int fallbackKeyCount() { return decryptKeys.size() - 1; }

    public boolean isEncryptedWithActiveKey(String encryptedValue) {
        return encryptedValue != null && encryptedValue.startsWith(VERSION + ":" + activeKeyId + ":");
    }

    private String decryptV2(String encryptedValue) {
        String[] parts = encryptedValue.split(":", 3);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) throw failure();
        SecretKeySpec key = decryptKeys.get(parts[1]);
        if (key == null) throw new IllegalStateException("环境密钥解密失败：密钥版本不可用");
        return decryptPayload(parts[2], key);
    }

    private String decryptLegacy(String encryptedValue) {
        String payload = encryptedValue.substring((LEGACY_VERSION + ":").length());
        IllegalStateException failure = null;
        for (SecretKeySpec key : decryptKeys.values()) {
            try {
                return decryptPayload(payload, key);
            } catch (IllegalStateException exception) {
                failure = exception;
            }
        }
        throw failure == null ? failure() : failure;
    }

    private String encryptPayload(String plainText, SecretKeySpec key) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("环境密钥加密失败", exception);
        }
    }

    private String decryptPayload(String encodedPayload, SecretKeySpec key) {
        try {
            byte[] payload = Base64.getUrlDecoder().decode(encodedPayload);
            if (payload.length <= NONCE_LENGTH_BYTES) throw failure();
            byte[] nonce = java.util.Arrays.copyOfRange(payload, 0, NONCE_LENGTH_BYTES);
            byte[] cipherText = java.util.Arrays.copyOfRange(payload, NONCE_LENGTH_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw failure(exception);
        }
    }

    private SecretKeySpec decodeKey(String encodedKey, String source) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(source + " 必须是 Base64 编码", exception);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalStateException(source + " 解码后必须为 16、24 或 32 字节");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private String requireKeyId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[a-zA-Z0-9_-]{1,32}")) {
            throw new IllegalStateException("加密密钥标识必须为 1-32 位字母、数字、下划线或连字符");
        }
        return normalized;
    }

    private IllegalStateException failure() { return new IllegalStateException("环境密钥解密失败"); }

    private IllegalStateException failure(Exception cause) { return new IllegalStateException("环境密钥解密失败", cause); }
}

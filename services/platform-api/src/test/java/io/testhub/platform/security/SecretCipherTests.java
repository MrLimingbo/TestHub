package io.testhub.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretCipherTests {
    private static final String OLD_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String NEW_KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

    @Test
    void usesKeyedCiphertextAndDecryptsFallbackAndLegacyPayloadsDuringRotation() throws Exception {
        SecretCipher oldCipher = new SecretCipher(OLD_KEY, "key-2025", "");
        SecretCipher rotatingCipher = new SecretCipher(NEW_KEY, "key-2026", "key-2025:" + OLD_KEY);
        String previousCiphertext = oldCipher.encrypt("previous-value");
        String activeCiphertext = rotatingCipher.encrypt("current-value");

        assertTrue(activeCiphertext.startsWith("v2:key-2026:"));
        assertTrue(rotatingCipher.isEncryptedWithActiveKey(activeCiphertext));
        assertFalse(rotatingCipher.isEncryptedWithActiveKey(previousCiphertext));
        assertEquals("previous-value", rotatingCipher.decrypt(previousCiphertext));
        assertEquals("legacy-value", rotatingCipher.decrypt(legacyPayload(OLD_KEY, "legacy-value")));
        assertThrows(IllegalStateException.class, () -> new SecretCipher(NEW_KEY, "key-2026", "").decrypt(previousCiphertext));
    }

    private String legacyPayload(String encodedKey, String plainText) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(encodedKey), "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, payload, 0, nonce.length);
        System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
        return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }
}

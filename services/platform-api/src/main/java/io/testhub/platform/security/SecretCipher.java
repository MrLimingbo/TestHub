package io.testhub.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {
    private static final String VERSION = "v1";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${testhub.security.encryption-key}") String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("TESTHUB_ENCRYPTION_KEY 必须是 Base64 编码", exception);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalStateException("TESTHUB_ENCRYPTION_KEY 解码后必须为 16、24 或 32 字节");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plainText) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return VERSION + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("环境密钥加密失败", exception);
        }
    }
}

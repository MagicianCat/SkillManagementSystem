package com.company.skillplatform.auth.application;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeishuTokenCipher {
    private final String configuredKey;
    private final FeishuProperties config;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    public FeishuTokenCipher(@Value("${skill-platform.feishu.token-encryption-key:${FEISHU_TOKEN_ENCRYPTION_KEY:}}") String configuredKey,
                             FeishuProperties config) {
        this.configuredKey = configuredKey;
        this.config = config;
    }

    @PostConstruct
    void initialize() {
        if (configuredKey == null || configuredKey.isBlank()) {
            if (!config.enabled()) {
                key = new SecretKeySpec(new byte[32], "AES");
                return;
            }
            throw new IllegalStateException("FEISHU_TOKEN_ENCRYPTION_KEY is required when Feishu is enabled");
        }
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(configuredKey); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("FEISHU_TOKEN_ENCRYPTION_KEY must be base64", ex); }
        if (decoded.length != 32) throw new IllegalStateException("FEISHU_TOKEN_ENCRYPTION_KEY must decode to 32 bytes");
        key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (Exception ex) { throw new IllegalStateException("Unable to encrypt Feishu credential", ex); }
    }

    public String decrypt(String value) {
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException("Unsupported ciphertext version");
            byte[] packed = Base64.getDecoder().decode(value.substring(3));
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Unable to decrypt Feishu credential", ex); }
    }
}

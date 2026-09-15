package com.company.skillplatform.telemetry.infrastructure;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Application-level encryption for telemetry identity snapshots. */
@Component
public class TelemetryCipher {
    private final String configuredKey;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    public TelemetryCipher(@Value("${skill-platform.telemetry.encryption-key:${SKILL_USAGE_ENCRYPTION_KEY:${FEISHU_TOKEN_ENCRYPTION_KEY:}}}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    @PostConstruct
    void initialize() {
        if (configuredKey == null || configuredKey.isBlank()) { key = new SecretKeySpec(new byte[32], "AES"); return; }
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey);
            if (decoded.length != 32) throw new IllegalArgumentException("wrong key length");
            key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("SKILL_USAGE_ENCRYPTION_KEY must be base64 and decode to 32 bytes", ex);
        }
    }

    public String encrypt(String value) {
        return new String(encryptBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public byte[] encryptBytes(byte[] value) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value);
            return ("v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array())).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt telemetry identity", ex);
        }
    }

    public byte[] decryptBytes(byte[] value) {
        try {
            String encoded = new String(value, java.nio.charset.StandardCharsets.US_ASCII);
            if (!encoded.startsWith("v1:")) throw new IllegalArgumentException("Unsupported ciphertext version");
            byte[] packed = Base64.getDecoder().decode(encoded.substring(3));
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return cipher.doFinal(encrypted);
        } catch (Exception ex) { throw new IllegalStateException("Unable to decrypt telemetry object", ex); }
    }
}

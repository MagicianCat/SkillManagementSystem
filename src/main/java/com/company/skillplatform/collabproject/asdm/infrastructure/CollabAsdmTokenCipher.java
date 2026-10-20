package com.company.skillplatform.collabproject.asdm.infrastructure;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Encrypts project-scoped ASDM tokens with a key independent from Feishu and JWT keys. */
@Component
public class CollabAsdmTokenCipher {
    private final CollabAsdmProperties properties;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    public CollabAsdmTokenCipher(CollabAsdmProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        String encoded = properties.tokenEncryptionKey();
        if (encoded == null || encoded.isBlank()) {
            if (properties.enabled()) throw new IllegalStateException("ASDM_GITLAB_TOKEN_ENCRYPTION_KEY is required when ASDM GitLab is enabled");
            return;
        }
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("ASDM_GITLAB_TOKEN_ENCRYPTION_KEY must be base64", ex); }
        if (decoded.length != 32) throw new IllegalStateException("ASDM_GITLAB_TOKEN_ENCRYPTION_KEY must decode to exactly 32 bytes");
        key = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    public String encrypt(String token) {
        if (key == null) throw new IllegalStateException("ASDM GitLab token encryption is not configured");
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception ex) { throw new IllegalStateException("Unable to encrypt ASDM credential", ex); }
    }

    public String decrypt(String value) {
        if (key == null) throw new IllegalStateException("ASDM GitLab token encryption is not configured");
        try {
            if (value == null || !value.startsWith("v1:")) throw new IllegalArgumentException("Unsupported ciphertext version");
            byte[] packed = Base64.getDecoder().decode(value.substring(3));
            if (packed.length < 12 + 16) throw new IllegalArgumentException("Invalid ciphertext length");
            byte[] iv = Arrays.copyOfRange(packed, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            try { return new String(plain, StandardCharsets.UTF_8); }
            finally { Arrays.fill(plain, (byte) 0); Arrays.fill(packed, (byte) 0); }
        } catch (Exception ex) { throw new IllegalStateException("Unable to decrypt ASDM credential", ex); }
    }
}

package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FeishuTokenCipherTest {
    private static FeishuProperties config(boolean enabled) {
        return new FeishuProperties(enabled, "app", "secret", "http://localhost/callback", false, "", "authorize",
                "token", "user", "api", false, Duration.ofMinutes(5));
    }

    @Test
    void encryptAndDecryptRoundTrip() {
        FeishuTokenCipher cipher = new FeishuTokenCipher(
                Base64.getEncoder().encodeToString(new byte[32]), config(true));
        cipher.initialize();

        String encrypted = cipher.encrypt("user-access-token");
        assertThat(encrypted).startsWith("v1:").doesNotContain("user-access-token");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("user-access-token");
    }

    @Test
    void tamperedCiphertextIsRejected() {
        FeishuTokenCipher cipher = new FeishuTokenCipher(
                Base64.getEncoder().encodeToString(new byte[32]), config(true));
        cipher.initialize();
        String encrypted = cipher.encrypt("refresh-token");
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledFeishuAllowsLocalStartupWithoutEncryptionKey() {
        FeishuTokenCipher cipher = new FeishuTokenCipher("", config(false));
        cipher.initialize();
        assertThat(cipher.decrypt(cipher.encrypt("local-test"))).isEqualTo("local-test");
    }
}

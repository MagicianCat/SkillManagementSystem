package com.company.skillplatform.auth.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FeishuPropertiesTest {
    private static FeishuProperties properties(String redirectUri, boolean mock, String mockBaseUrl) {
        return new FeishuProperties(true, "app", "secret", redirectUri, mock, mockBaseUrl,
                "authorize", "token", "user", "api", false, Duration.ofMinutes(5));
    }

    @Test
    void defaultsToLocalFrontendCallbackWhenNoCallbackIsConfigured() {
        assertEquals("http://127.0.0.1:5173/oauth/callback",
                properties("", false, "").effectiveRedirectUri());
    }

    @Test
    void usesConfiguredLocalCallbackByDefault() {
        assertEquals("http://127.0.0.1:5173/oauth/callback",
                properties("http://127.0.0.1:5173/oauth/callback", false, "https://tunnel.example").effectiveRedirectUri());
    }

    @Test
    void usesMockHttpsCallbackOnlyWhenEnabled() {
        assertEquals("https://tunnel.example/oauth/callback",
                properties("http://127.0.0.1:5173/oauth/callback", true, "https://tunnel.example/").effectiveRedirectUri());
    }
}

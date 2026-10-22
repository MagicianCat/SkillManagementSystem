package com.company.skillplatform.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class FeishuCardInternalSignature {
    private static final long CLOCK_SKEW_MS = 60_000L;
    private static final int MAX_NONCES = 20_000;
    private final ConcurrentHashMap<String, Long> consumedNonces = new ConcurrentHashMap<>();

    public String sign(String secret, String timestamp, String nonce, String environment, byte[] body) {
        return "v1=" + HexFormat.of().formatHex(hmac(secret, signingText(timestamp, nonce, environment, body)));
    }

    public boolean verifyAndConsume(String secret, String timestamp, String nonce, String environment,
                                    String suppliedSignature, byte[] body) {
        if (secret == null || secret.isBlank() || timestamp == null || nonce == null || nonce.isBlank()
                || nonce.length() > 200 || suppliedSignature == null || !suppliedSignature.startsWith("v1=")) return false;
        long timestampMs;
        try { timestampMs = Long.parseLong(timestamp); } catch (NumberFormatException invalid) { return false; }
        long now = Instant.now().toEpochMilli();
        if (timestampMs < now - CLOCK_SKEW_MS || timestampMs > now + CLOCK_SKEW_MS) return false;
        String expected = sign(secret, timestamp, nonce, environment, body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), suppliedSignature.getBytes(StandardCharsets.US_ASCII))) return false;
        synchronized (consumedNonces) {
            consumedNonces.entrySet().removeIf(entry -> entry.getValue() < now);
            long expiresAt = Math.max(now, timestampMs) + CLOCK_SKEW_MS;
            if (consumedNonces.size() >= MAX_NONCES || consumedNonces.putIfAbsent(environment + ":" + nonce, expiresAt) != null) return false;
        }
        return true;
    }

    private static String signingText(String timestamp, String nonce, String environment, byte[] body) {
        return timestamp + "\n" + nonce + "\n" + environment + "\n" + HexFormat.of().formatHex(sha256(body));
    }
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static byte[] hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException("Unable to sign internal Feishu callback", error); }
    }
}

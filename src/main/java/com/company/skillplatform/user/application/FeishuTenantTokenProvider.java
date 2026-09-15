package com.company.skillplatform.user.application;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 管理飞书 tenant_access_token 的生命周期，避免长任务复用已失效 token。 */
@Component
public class FeishuTenantTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(FeishuTenantTokenProvider.class);
    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    private final FeishuProperties config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile CachedToken cached;

    public FeishuTenantTokenProvider(FeishuProperties config, ObjectMapper json) {
        this.config = config;
        this.json = json;
    }

    public String getToken() {
        CachedToken current = cached;
        if (current != null && !current.expiresAt().minus(REFRESH_BEFORE_EXPIRY).isBefore(Instant.now())) {
            return current.value();
        }
        return refresh();
    }

    /** 飞书返回 token 无效时调用；只清理仍对应失败请求的 token，避免覆盖并发请求刚刷新的 token。 */
    public void invalidate(String failedToken) {
        CachedToken current = cached;
        if (current != null && current.value().equals(failedToken)) {
            cached = null;
        }
    }

    @Scheduled(fixedDelayString = "${skill-platform.feishu.token-refresh-interval-ms:300000}")
    public void refreshIfNeeded() {
        CachedToken current = cached;
        if (current == null || current.expiresAt().minus(REFRESH_BEFORE_EXPIRY).isBefore(Instant.now())) {
            try {
                refresh();
            } catch (Exception e) {
                log.warn("event=feishu.tenant_token.refresh_failed message={}", e.getMessage());
            }
        }
    }

    private String refresh() {
        refreshLock.lock();
        try {
            CachedToken current = cached;
            if (current != null && !current.expiresAt().minus(REFRESH_BEFORE_EXPIRY).isBefore(Instant.now())) {
                return current.value();
            }
            try {
                String body = json.writeValueAsString(Map.of("app_id", config.appId(), "app_secret", config.appSecret()));
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.apiBaseUrl() + "/auth/v3/tenant_access_token/internal"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode data = json.readTree(response.body());
                String token = text(data, "tenant_access_token", text(data.path("data"), "tenant_access_token", ""));
                long expiresIn = data.path("expire").asLong(data.path("data").path("expire").asLong(7200));
                if (response.statusCode() / 100 != 2 || data.path("code").asInt(0) != 0 || token.isBlank()) {
                    throw new IllegalStateException("Feishu tenant token exchange failed: HTTP " + response.statusCode() + " " + compact(response.body()));
                }
                Instant expiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn));
                cached = new CachedToken(token, expiresAt);
                log.info("event=feishu.tenant_token.refreshed expiresAt={} expiresInSeconds={}", expiresAt, expiresIn);
                return token;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Feishu tenant token refresh interrupted", e);
            } catch (Exception e) {
                if (e instanceof IllegalStateException state) throw state;
                throw new IllegalStateException("Feishu tenant token refresh failed: " + e.getMessage(), e);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private String compact(String body) {
        String value = body == null ? "" : body.replaceAll("[\\r\\n]", " ");
        return value.substring(0, Math.min(300, value.length()));
    }

    private record CachedToken(String value, Instant expiresAt) {}
}

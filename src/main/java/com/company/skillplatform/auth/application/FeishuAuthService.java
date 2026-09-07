package com.company.skillplatform.auth.application;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.auth.infrastructure.JwtTokenService;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class FeishuAuthService {
    private static final String PROVIDER = "feishu";
    private final FeishuProperties config;
    private final AuthService auth;
    private final JwtTokenService jwt;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    public FeishuAuthService(FeishuProperties config, AuthService auth, JwtTokenService jwt, ObjectMapper json) {
        this.config = config; this.auth = auth; this.jwt = jwt; this.json = json;
    }
    public boolean isConfigured() { return config.enabled() && !blank(config.appId()) && !blank(config.appSecret()) && !blank(config.redirectUri()); }
    public String authorize(String redirectPath) {
        enabled(); String safePath = safeRedirectPath(redirectPath); String nonce = UUID.randomUUID().toString();
        Duration ttl = stateTtl(); String state = jwt.createSignedState(PROVIDER, safePath, nonce, ttl);
        usedNonces.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis());
        usedNonces.put(nonce, System.currentTimeMillis() + ttl.toMillis());
        return config.authorizeUrl() + "?app_id=" + enc(config.appId()) + "&redirect_uri=" + enc(config.redirectUri())
                + "&response_type=code&state=" + enc(state);
    }
    public AuthService.TokenResult callback(String code, String state, String clientInfo) {
        enabled();
        if (blank(code) || blank(state)) throw error("FEISHU_CALLBACK_INVALID", "Feishu callback parameters are invalid", HttpStatus.BAD_REQUEST);
        Claims claims;
        try { claims = jwt.parse(state); } catch (Exception ex) { throw error("FEISHU_STATE_INVALID", "Feishu authorization state is invalid", HttpStatus.UNAUTHORIZED); }
        String nonce = claims.get("nonce", String.class);
        if (!"oauth_state".equals(claims.get("typ", String.class)) || !PROVIDER.equals(claims.get("provider", String.class))
                || blank(nonce) || usedNonces.remove(nonce) == null) throw error("FEISHU_STATE_INVALID", "Feishu authorization state is invalid", HttpStatus.UNAUTHORIZED);
        try {
            JsonNode token = json.readTree(send(config.tokenUrl(), json.writeValueAsString(Map.of(
                    "grant_type", "authorization_code", "client_id", config.appId(), "client_secret", config.appSecret(),
                    "code", code, "redirect_uri", config.redirectUri()))));
            ensureSuccess(token, "FEISHU_TOKEN_EXCHANGE_FAILED", "Feishu token exchange failed");
            String access = text(token, "access_token", text(token.path("data"), "access_token", ""));
            if (blank(access)) throw error("FEISHU_TOKEN_INVALID", "Feishu token exchange failed", HttpStatus.BAD_GATEWAY);
            JsonNode info = json.readTree(sendGet(config.userInfoUrl(), access)); ensureSuccess(info, "FEISHU_USER_INFO_FAILED", "Feishu user information request failed");
            JsonNode data = info.path("data"); String openId = text(data, "open_id", ""); String userId = text(data, "user_id", ""); String unionId = text(data, "union_id", "");
            String external = !blank(openId) ? openId : (!blank(userId) ? userId : unionId);
            if (blank(external)) throw error("FEISHU_USER_INVALID", "Feishu user information is incomplete", HttpStatus.BAD_GATEWAY);
            String email = text(data, "email", ""); String name = text(data, "name", external);
            return auth.loginFeishu(new AuthService.FeishuIdentity(external, openId, userId, "feishu_" + external, name, blank(email) ? null : email, unionId), clientInfo);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw error("FEISHU_UNAVAILABLE", "Feishu is unavailable", HttpStatus.BAD_GATEWAY);
        } catch (BusinessException ex) { throw ex; } catch (Exception ex) { throw error("FEISHU_LOGIN_FAILED", "Feishu login failed", HttpStatus.BAD_GATEWAY); }
    }
    private String send(String url, String body) throws Exception { HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() / 100 != 2) throw error("FEISHU_UPSTREAM_HTTP_ERROR", "Feishu request failed", HttpStatus.BAD_GATEWAY); return response.body(); }
    private String sendGet(String url, String access) throws Exception { HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + access).GET().build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() / 100 != 2) throw error("FEISHU_UPSTREAM_HTTP_ERROR", "Feishu request failed", HttpStatus.BAD_GATEWAY); return response.body(); }
    private void ensureSuccess(JsonNode node, String code, String message) { if (node.has("code") && node.path("code").asInt(0) != 0) throw error(code, message, HttpStatus.BAD_GATEWAY); }
    private String safeRedirectPath(String path) { if (blank(path) || !path.startsWith("/") || path.startsWith("//") || path.contains("\n") || path.contains("\r")) return "/skills"; return path; }
    private Duration stateTtl() { return config.oauthStateTtl() == null ? Duration.ofMinutes(5) : config.oauthStateTtl(); }
    private void enabled() { if (!isConfigured()) throw error("FEISHU_NOT_CONFIGURED", "Feishu login is not configured", HttpStatus.SERVICE_UNAVAILABLE); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String text(JsonNode node, String key, String fallback) { JsonNode value = node.get(key); return value == null || value.isNull() ? fallback : value.asText(fallback); }
    private static BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
}

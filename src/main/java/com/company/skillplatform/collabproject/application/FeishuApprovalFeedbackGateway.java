package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.user.application.FeishuTenantTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Read-only Feishu API access used by the approval feedback export. */
@Component
public class FeishuApprovalFeedbackGateway {
    private final FeishuProperties config;
    private final FeishuTenantTokenProvider tenantTokens;
    private final FeishuUserTokenService userTokens;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FeishuApprovalFeedbackGateway(FeishuProperties config, FeishuTenantTokenProvider tenantTokens,
            FeishuUserTokenService userTokens, ObjectMapper json) {
        this.config = config; this.tenantTokens = tenantTokens; this.userTokens = userTokens; this.json = json;
    }

    public ApiResult listMessages(String chatId, long startTime, long endTime, String pageToken) {
        String path = "/im/v1/messages?container_id_type=chat&container_id=" + enc(chatId)
                + "&start_time=" + startTime + "&end_time=" + endTime
                + "&sort_type=ByCreateTimeAsc&page_size=50" + tokenQuery(pageToken);
        return get(path, tenantTokens.getToken());
    }

    public ApiResult listThread(String chatId, String rootMessageId, String pageToken) {
        String path = "/im/v1/messages?container_id_type=thread&container_id=" + enc(rootMessageId)
                + "&page_size=50&sort_type=ByCreateTimeAsc" + tokenQuery(pageToken);
        return get(path, tenantTokens.getToken());
    }

    public ApiResult listComments(Long userId, String documentToken, String pageToken) {
        String token = userTokens.accessTokenFor(userId);
        if (token == null || token.isBlank()) return ApiResult.failure(401, "FEISHU_USER_AUTH_REQUIRED");
        String path = "/drive/v1/files/" + enc(documentToken) + "/comments?file_type=docx&page_size=50" + tokenQuery(pageToken);
        return get(path, token);
    }

    private ApiResult get(String path, String accessToken) {
        try {
            URI uri = URI.create(config.apiBaseUrl().replaceAll("/$", "") + path);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + accessToken).GET().build();
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    if (attempt == 2) return ApiResult.failure(429, "Feishu API rate limit reached");
                    Thread.sleep(250L << attempt);
                    continue;
                }
                JsonNode body = json.readTree(response.body());
                int code = body.path("code").asInt(response.statusCode() / 100 == 2 ? 0 : response.statusCode());
                if (response.statusCode() / 100 != 2 || code != 0)
                    return ApiResult.failure(code, safeMessage(body.path("msg").asText("Feishu API request failed")));
                return ApiResult.success(body);
            }
            return ApiResult.failure(429, "Feishu API rate limit reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return ApiResult.failure(0, "FEISHU_REQUEST_INTERRUPTED");
        } catch (Exception e) {
            return ApiResult.failure(0, "FEISHU_REQUEST_FAILED");
        }
    }

    private static String tokenQuery(String token) {
        return token == null || token.isBlank() ? "" : "&page_token=" + enc(token);
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) return "Feishu API request failed";
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    public record ApiResult(boolean ok, int code, String message, JsonNode body) {
        static ApiResult success(JsonNode body) { return new ApiResult(true, 0, "", body); }
        static ApiResult failure(int code, String message) { return new ApiResult(false, code, message, null); }
    }
}

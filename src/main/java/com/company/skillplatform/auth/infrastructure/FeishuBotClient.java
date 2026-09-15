package com.company.skillplatform.auth.infrastructure;

import com.company.skillplatform.user.application.FeishuTenantTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Sends bot replies with the tenant token; user access tokens never enter this adapter. */
@Component
public class FeishuBotClient {
    private final FeishuProperties properties;
    private final FeishuTenantTokenProvider tokens;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FeishuBotClient(FeishuProperties properties, FeishuTenantTokenProvider tokens, ObjectMapper json) {
        this.properties = properties; this.tokens = tokens; this.json = json;
    }

    public String reply(String messageId, String msgType, Object content, String uuid, boolean replyInThread) {
        String token = tokens.getToken();
        try {
            String body = json.writeValueAsString(Map.of("msg_type", msgType, "content", json.writeValueAsString(content),
                    "uuid", uuid, "reply_in_thread", replyInThread));
            String url = properties.apiBaseUrl().replaceAll("/$", "") + "/im/v1/messages/" + messageId + "/reply";
            HttpResponse<String> response = send(url, token, body);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                tokens.invalidate(token);
                response = send(url, tokens.getToken(), body);
            }
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() / 100 != 2 || result.path("code").asInt(0) != 0) {
                throw new IllegalStateException("Feishu bot reply failed: HTTP " + response.statusCode() + " code=" + result.path("code").asInt(-1));
            }
            return result.path("data").path("message_id").asText("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feishu bot reply interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Feishu bot reply failed", e);
        }
    }

    private HttpResponse<String> send(String url, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

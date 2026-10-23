package com.company.skillplatform.collabproject.application;

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
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Small tenant-token adapter for proactive collaboration chat operations. */
@Component
public class CollabFeishuChatGateway {
    private final FeishuProperties config;
    private final FeishuTenantTokenProvider tokens;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CollabFeishuChatGateway(FeishuProperties config, FeishuTenantTokenProvider tokens, ObjectMapper json) {
        this.config = config;
        this.tokens = tokens;
        this.json = json;
    }

    public String createChat(String name) {
        JsonNode result = request("POST", "/im/v1/chats", Map.of("name", name));
        String chatId = result.path("data").path("chat_id").asText("");
        if (chatId.isBlank()) throw new IllegalStateException("Feishu did not return chat_id");
        return chatId;
    }

    public void addMember(String chatId, String openId) {
        request("POST", "/im/v1/chats/" + segment(chatId) + "/members?member_id_type=open_id",
                Map.of("id_list", List.of(openId)));
    }

    public void removeMember(String chatId, String openId) {
        request("DELETE", "/im/v1/chats/" + segment(chatId) + "/members?member_id_type=open_id",
                Map.of("id_list", List.of(openId)));
    }

    public void sendText(String chatId, String content, String dedupeKey) {
        try {
            request("POST", "/im/v1/messages?receive_id_type=chat_id",
                    Map.of("receive_id", chatId, "msg_type", "text",
                            "content", json.writeValueAsString(Map.of("text", content)), "uuid", dedupeKey));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid chat message", e);
        }
    }

    public void sendInteractive(String chatId, Object card, String dedupeKey) {
        try {
            request("POST", "/im/v1/messages?receive_id_type=chat_id",
                    Map.of("receive_id", chatId, "msg_type", "interactive",
                            "content", json.writeValueAsString(card), "uuid", dedupeKey));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid interactive card", e);
        }
    }

    private JsonNode request(String method, String path, Object body) {
        String token = tokens.getToken();
        JsonNode result = send(method, path, body, token);
        if (result.path("code").asInt(-1) == 99991663 || result.path("code").asInt(-1) == 99991668) {
            tokens.invalidate(token);
            result = send(method, path, body, tokens.getToken());
        }
        if (result.path("code").asInt(-1) != 0)
            throw new IllegalStateException("Feishu chat API error code=" + result.path("code").asInt(-1));
        return result;
    }

    private JsonNode send(String method, String path, Object body, String token) {
        try {
            String url = config.apiBaseUrl().replaceAll("/$", "") + path;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
            String encoded = json.writeValueAsString(body);
            builder.method(method, HttpRequest.BodyPublishers.ofString(encoded));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() / 100 != 2
                    && result.path("code").asInt(-1) != 99991663
                    && result.path("code").asInt(-1) != 99991668)
                throw new IllegalStateException("Feishu chat HTTP " + response.statusCode()
                        + " code=" + result.path("code").asInt(-1)
                        + " msg=" + result.path("msg").asText("").replaceAll("[\\r\\n]", " "));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feishu chat request interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Feishu chat request failed", e);
        }
    }

    private String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}

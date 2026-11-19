package com.company.skillplatform.wiki.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Single-question adapter for the deployed decision model; Wiki text is never logged. */
@Component
public class DecisionModelClient {
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI uri;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public DecisionModelClient(ObjectMapper json,
            @Value("${agent.intent.endpoint}") String endpoint,
            @Value("${agent.intent.api-key:${DECISION_MODEL_API_KEY:}}") String apiKey,
            @Value("${agent.intent.model}") String model,
            @Value("${wiki.category.suggest.timeout-ms:5000}") long timeoutMs) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.uri = URI.create(endpoint.replaceAll("/$", "") + "/v1/systemone");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
    }

    public String model() { return model; }

    public JsonNode choose(String state, Map<String, Object> questions) {
        if (apiKey == null || apiKey.isBlank()) throw new DecisionModelException("WIKI_CATEGORY_MODEL_NOT_CONFIGURED");
        try {
            String body = json.writeValueAsString(Map.of("model", model, "state", state, "questions", questions));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new DecisionModelException("WIKI_CATEGORY_MODEL_HTTP_" + response.statusCode());
            return json.readTree(response.body());
        } catch (DecisionModelException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DecisionModelException("WIKI_CATEGORY_MODEL_UNAVAILABLE", failure);
        } catch (Exception failure) {
            throw new DecisionModelException("WIKI_CATEGORY_MODEL_UNAVAILABLE", failure);
        }
    }

    public static class DecisionModelException extends RuntimeException {
        private final String code;
        public DecisionModelException(String code) { super(code); this.code = code; }
        public DecisionModelException(String code, Throwable cause) { super(code, cause); this.code = code; }
        public String code() { return code; }
    }
}

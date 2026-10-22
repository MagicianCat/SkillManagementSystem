package com.company.skillplatform.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FeishuCardCallbackProxy {
    private final FeishuCardProperties properties;
    private final FeishuCardInternalSignature signatures;
    private final ObjectMapper objectMapper;

    public FeishuCardCallbackProxy(FeishuCardProperties properties, FeishuCardInternalSignature signatures, ObjectMapper objectMapper) {
        this.properties = properties; this.signatures = signatures; this.objectMapper = objectMapper;
    }

    public P2CardActionTriggerResponse forward(String environment, FeishuCardCallbackEnvelope envelope) throws Exception {
        FeishuCardProperties.Route route = route(environment);
        if (route == null || !route.isEnabled()) throw new IllegalStateException("Feishu callback target is disabled");
        String secret = secret(environment);
        byte[] body = objectMapper.writeValueAsBytes(envelope);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String signature = signatures.sign(secret, timestamp, nonce, environment, body);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getForward().getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(route.getUrl()))
                .timeout(Duration.ofMillis(properties.getForward().getTotalTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-SMS-Callback-Env", environment)
                .header("X-SMS-Callback-Timestamp", timestamp)
                .header("X-SMS-Callback-Nonce", nonce)
                .header("X-SMS-Callback-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IllegalStateException("Feishu callback target returned HTTP " + response.statusCode());
        FeishuCardCallbackEnvelope.Response dto = objectMapper.readValue(response.body(), FeishuCardCallbackEnvelope.Response.class);
        return FeishuCardCallbackEnvelope.toSdkResponse(dto);
    }

    private FeishuCardProperties.Route route(String environment) {
        return switch (environment) {
            case "local" -> properties.getRouteLocal();
            case "prod" -> properties.getRouteProd();
            default -> null;
        };
    }
    private String secret(String environment) {
        return switch (environment) {
            case "local" -> properties.getSecurity().getLocalSecret();
            case "prod" -> properties.getSecurity().getProdSecret();
            default -> throw new IllegalArgumentException("Testing callbacks are dispatched directly");
        };
    }
}

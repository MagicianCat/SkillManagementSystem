package com.company.skillplatform.collabproject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.user.application.FeishuTenantTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FeishuApprovalFeedbackGatewayTest {
    @Test
    void retriesRateLimitedHistoryReads() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/im/v1/messages", exchange -> {
            int status = calls.incrementAndGet() == 1 ? 429 : 200;
            byte[] response = status == 429 ? "rate limited".getBytes(StandardCharsets.UTF_8)
                    : "{\"code\":0,\"data\":{\"items\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            FeishuProperties config = new FeishuProperties(true, "app", "secret", "", false, "",
                    "", "", "", "http://127.0.0.1:" + server.getAddress().getPort(),
                    false, Duration.ofMinutes(10));
            FeishuTenantTokenProvider tokens = mock(FeishuTenantTokenProvider.class);
            when(tokens.getToken()).thenReturn("tenant-test-token");
            FeishuApprovalFeedbackGateway gateway = new FeishuApprovalFeedbackGateway(
                    config, tokens, mock(FeishuUserTokenService.class), new ObjectMapper());

            assertThat(gateway.listMessages("oc_chat", 1, 2, null).ok()).isTrue();
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void threadHistoryUsesThreadIdAsTheThreadContainerId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requestedUri = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/im/v1/messages", exchange -> {
            requestedUri.set(exchange.getRequestURI().toString());
            byte[] response = "{\"code\":0,\"data\":{\"items\":[],\"has_more\":false}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            FeishuProperties config = new FeishuProperties(true, "app", "secret", "", false, "",
                    "", "", "", "http://127.0.0.1:" + server.getAddress().getPort(),
                    false, Duration.ofMinutes(10));
            FeishuTenantTokenProvider tenantTokens = mock(FeishuTenantTokenProvider.class);
            when(tenantTokens.getToken()).thenReturn("tenant-test-token");
            FeishuApprovalFeedbackGateway gateway = new FeishuApprovalFeedbackGateway(
                    config, tenantTokens, mock(FeishuUserTokenService.class), new ObjectMapper());

            var result = gateway.listThread("oc_chat", "omt_thread_identifier", null);

            assertThat(result.ok()).isTrue();
            assertThat(requestedUri.get()).contains("container_id_type=thread")
                    .contains("container_id=omt_thread_identifier");
            assertThat(requestedUri.get()).doesNotContain("container_id=oc_chat");
        } finally {
            server.stop(0);
        }
    }
}

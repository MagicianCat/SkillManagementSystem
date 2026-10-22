package com.company.skillplatform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeishuDocumentSharingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sharesDocxWithRequirementChatAsViewerAndEnablesViewerComments() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(requests);
        try {
            FeishuDocumentMcpProxy proxy = proxy(server);

            proxy.grantChatReadAccess(9L, "docx-token", "docx", "oc_test_chat");

            assertThat(requests).hasSize(2);
            assertThat(requests.get(0).method()).isEqualTo("POST");
            assertThat(requests.get(0).path()).isEqualTo("/open-apis/drive/v1/permissions/docx-token/members");
            assertThat(requests.get(0).query()).contains("type=docx", "need_notification=false");
            assertThat(requests.get(0).body()).containsEntry("member_type", "openchat")
                    .containsEntry("member_id", "oc_test_chat").containsEntry("perm", "view");
            assertThat(requests.get(1).method()).isEqualTo("PATCH");
            assertThat(requests.get(1).path()).isEqualTo("/open-apis/drive/v1/permissions/docx-token/public");
            assertThat(requests.get(1).query()).isEqualTo("type=docx");
            assertThat(requests.get(1).body()).containsEntry("comment_entity", "anyone_can_view");
        } finally { server.stop(0); }
    }

    @Test
    void sharesDriveFilesAsViewOnlyWithoutChangingCommentPolicy() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = server(requests);
        try {
            proxy(server).grantChatReadAccess(9L, "file-token", "file", "oc_test_chat");

            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).query()).contains("type=file");
            assertThat(requests.get(0).body()).containsEntry("member_type", "openchat")
                    .containsEntry("member_id", "oc_test_chat").containsEntry("perm", "view");
        } finally { server.stop(0); }
    }

    private FeishuDocumentMcpProxy proxy(HttpServer server) {
        FeishuUserTokenService tokens = mock(FeishuUserTokenService.class);
        when(tokens.accessTokenFor(9L)).thenReturn("user-token");
        return new FeishuDocumentMcpProxy(tokens, json, "http://127.0.0.1/mcp",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis");
    }

    private HttpServer server(List<Request> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/drive/v1/permissions/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode parsed = json.readTree(body);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), json.convertValue(parsed,
                    json.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class))));
            byte[] response = "{\"code\":0,\"msg\":\"success\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }

    private record Request(String method, String path, String query, java.util.Map<String, Object> body) {}
}

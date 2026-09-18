package com.company.skillplatform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.application.FeishuUserTokenService;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeishuDocumentMcpProxyTest {
    private final FeishuUserTokenService tokens = mock(FeishuUserTokenService.class);
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    @Test
    void searchUsesUserScopedDriveApiAndReturnsDataOnly() {
        server.createContext("/open-apis/suite/docs-api/search/object", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer user-token");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("\"search_key\":\"研发流程\"");
            assertThat(request).contains("\"count\":10");
            assertThat(request).contains("\"offset\":20");
            byte[] response = "{\"code\":0,\"data\":{\"files\":[{\"name\":\"研发流程\",\"docs_token\":\"doc-token\",\"docs_type\":\"docx\"}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        when(tokens.accessTokenFor(7L)).thenReturn("user-token");
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis";
        FeishuDocumentMcpProxy proxy = new FeishuDocumentMcpProxy(tokens, new ObjectMapper(), "http://127.0.0.1:1/mcp", base);

        JsonNode result = proxy.search(7L, "研发流程", 10, 20);
        assertThat(result.path("files").get(0).path("name").asText()).isEqualTo("研发流程");
        assertThat(result.path("files").get(0).path("open_url").asText()).isEqualTo("https://www.feishu.cn/docx/doc-token");
        assertThat(result.path("files").get(0).path("open_url_source").asText()).isEqualTo("DERIVED_FROM_TOKEN");
        assertThat(result.path("next_offset").asInt()).isEqualTo(21);
        assertThat(result.path("files").get(0).path("docType").asText()).isEqualTo("DOCX");
        assertThat(result.path("files").get(0).path("readable").asBoolean()).isTrue();
    }

    @Test
    void readsLegacyDocsThroughLegacyApiWithPagination() {
        server.createContext("/open-apis/doc/v2/legacy-token/raw_content", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer user-token");
            byte[] response = "{\"code\":0,\"data\":{\"content\":\"0123456789\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        when(tokens.accessTokenFor(7L)).thenReturn("user-token");
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis";
        FeishuDocumentMcpProxy proxy = new FeishuDocumentMcpProxy(tokens, new ObjectMapper(), "http://127.0.0.1:1/mcp", base);

        JsonNode result = proxy.fetch(7L, "legacy-token", "DOC", 3, 4);
        assertThat(result.path("status").asText()).isEqualTo("OK");
        assertThat(result.path("docType").asText()).isEqualTo("DOC");
        assertThat(result.path("content").asText()).isEqualTo("3456");
        assertThat(result.path("hasMore").asBoolean()).isTrue();
    }

    @Test
    void resolvesWikiNodeBeforeReadingUnderlyingLegacyDocument() {
        server.createContext("/open-apis/wiki/v2/spaces/get_node", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("token=TcimwIdVviNq6YkgNYycs8v3nge");
            byte[] response = "{\"code\":0,\"data\":{\"node\":{\"obj_token\":\"legacy-token\",\"obj_type\":\"doc\",\"title\":\"需求说明\"}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/open-apis/doc/v2/legacy-token/raw_content", exchange -> {
            byte[] response = "{\"code\":0,\"data\":{\"content\":\"wiki-content\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        when(tokens.accessTokenFor(7L)).thenReturn("user-token");
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis";
        FeishuDocumentMcpProxy proxy = new FeishuDocumentMcpProxy(tokens, new ObjectMapper(), "http://127.0.0.1:1/mcp", base);

        JsonNode result = proxy.fetch(7L, "TcimwIdVviNq6YkgNYycs8v3nge", "WIKI", 0, 1024);
        assertThat(result.path("status").asText()).isEqualTo("OK");
        assertThat(result.path("docId").asText()).isEqualTo("TcimwIdVviNq6YkgNYycs8v3nge");
        assertThat(result.path("docType").asText()).isEqualTo("WIKI");
        assertThat(result.path("resolvedDocId").asText()).isEqualTo("legacy-token");
        assertThat(result.path("title").asText()).isEqualTo("需求说明");
        assertThat(result.path("content").asText()).isEqualTo("wiki-content");
    }

    @Test
    void unsupportedDocumentTypesDoNotCallUpstream() {
        when(tokens.accessTokenFor(7L)).thenReturn("user-token");
        FeishuDocumentMcpProxy proxy = new FeishuDocumentMcpProxy(tokens, new ObjectMapper(), "http://127.0.0.1:1/mcp", "http://127.0.0.1:1/open-apis");
        JsonNode result = proxy.fetch(7L, "sheet-token", "SHEET", 0, 1024);
        assertThat(result.path("status").asText()).isEqualTo("UNSUPPORTED_DOCUMENT_TYPE");
        assertThat(result.path("readable").asBoolean()).isFalse();
    }

    @Test
    void classifiesRateLimitAsRetryableUpstreamFailure() {
        server.createContext("/open-apis/suite/docs-api/search/object", exchange -> {
            byte[] response = "{\"code\":429,\"msg\":\"busy\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        when(tokens.accessTokenFor(7L)).thenReturn("user-token");
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis";
        FeishuDocumentMcpProxy proxy = new FeishuDocumentMcpProxy(tokens, new ObjectMapper(), "http://127.0.0.1:1/mcp", base);

        assertThatThrownBy(() -> proxy.search(7L, "研发流程", 10, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FEISHU_RATE_LIMITED"));
    }
}

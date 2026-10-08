package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphWorkerClientTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void submitsStableIdempotencyKeyAndBearerToken() throws Exception {
        var authorization = new AtomicReference<String>();
        var idempotency = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/code-graph/build", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            var body = "{\"schemaVersion\":1,\"engineJobId\":\"job-1\",\"status\":\"QUEUED\",\"acceptedAt\":\"2026-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var properties = new CodeGraphWorkerProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret", Duration.ofSeconds(2), Duration.ofSeconds(5), 2, 4, 1800, 600);
        var client = new CodeGraphWorkerClient(RestClient.builder(), properties);
        var request = new CodeGraphEnginePort.BuildRequest("request-1", "bundle-1", List.of(
                new RepositoryInput("repo", "backend", "a".repeat(40), "b".repeat(40), "file:///source.tar", "c".repeat(64))), Map.of());

        assertThat(client.build(request).engineJobId()).isEqualTo("job-1");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
        assertThat(idempotency.get()).isEqualTo("request-1");
    }

    @Test
    void submitsMixedReusePlanInWorkerContract() throws Exception {
        var requestBody = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/code-graph/build", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = "{\"schemaVersion\":1,\"engineJobId\":\"job-mixed\",\"status\":\"QUEUED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var properties = new CodeGraphWorkerProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret", Duration.ofSeconds(2), Duration.ofSeconds(5), 2, 4, 1800, 600);
        var client = new CodeGraphWorkerClient(RestClient.builder(), properties);
        var reusePlan = Map.of("repositories", Map.of("backend", Map.of("mode", "INCREMENTAL",
                "baseCommitSha", "d".repeat(40), "baseTreeSha", "e".repeat(40),
                "baseArtifactUri", "file:///graph.tar.zst", "baseArtifactSha256", "f".repeat(64),
                "baseArtifactRepositoryAlias", "backend")));
        var request = new CodeGraphEnginePort.BuildRequest("request-mixed", "bundle-mixed", List.of(
                new RepositoryInput("repo", "backend", "a".repeat(40), "b".repeat(40), "file:///source.tar", "c".repeat(64))),
                Map.of("buildMode", "INCREMENTAL", "reusePlan", reusePlan));

        assertThat(client.build(request).engineJobId()).isEqualTo("job-mixed");
        assertThat(requestBody.get()).contains("\"buildMode\":\"INCREMENTAL\"")
                .contains("\"reusePlan\"")
                .contains("\"baseArtifactRepositoryAlias\":\"backend\"");
    }

    @Test
    void queriesWorkerWithServerResolvedGraphAndStructuredParameters() throws Exception {
        var body = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/code-graph/query", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = "{\"definitions\":[{\"name\":\"Controller\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        var properties = new CodeGraphWorkerProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(), "secret",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 2, 4, 1800, 600);
        var client = new CodeGraphWorkerClient(RestClient.builder(), properties);
        var result = client.query(new CodeGraphEnginePort.QueryRequest("query",
                new CodeGraphEnginePort.GraphRef("file:///managed/graph.tar.zst", "a".repeat(64),
                        List.of(new CodeGraphEnginePort.GraphRepository("backend", "backend"))), "backend", Map.of("query", "Controller", "limit", 5)));
        assertThat(result.data()).containsKey("definitions");
        assertThat(body.get()).contains("file:///managed/graph.tar.zst").contains("\"query\":\"Controller\"");
    }
}

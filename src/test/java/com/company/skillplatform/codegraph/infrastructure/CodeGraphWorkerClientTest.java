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
}

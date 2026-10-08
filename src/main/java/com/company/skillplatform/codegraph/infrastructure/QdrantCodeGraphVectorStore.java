package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorPoint;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorSearchFilter;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorSearchHit;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Qdrant adapter dedicated to code-graph semantic nodes.
 *
 * <p>The Qdrant endpoint and API key are intentionally shared with the knowledge
 * index, while the collection and domain contract are kept separate.</p>
 */
@Component
public class QdrantCodeGraphVectorStore implements CodeGraphVectorStorePort {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final KnowledgeProperties knowledge;
    private final CodeGraphSemanticProperties properties;
    private final MeterRegistry metrics;
    private final AtomicBoolean initialized = new AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    public QdrantCodeGraphVectorStore(RestClient.Builder builder, ObjectMapper mapper,
                                      KnowledgeProperties knowledge,
                                      CodeGraphSemanticProperties properties,
                                      MeterRegistry metrics) {
        var config = knowledge.getQdrant();
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.getConnectTimeout())
                        // Bypass system proxies for the local Qdrant sidecar.
                        .proxy(java.net.ProxySelector.of(null))
                        .build());
        factory.setReadTimeout(config.getRequestTimeout());
        this.client = builder.clone().requestFactory(factory).baseUrl(config.getBaseUrl()).build();
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** Package-private constructor for unit tests that pre-build the RestClient with a mock transport. */
    QdrantCodeGraphVectorStore(RestClient client, ObjectMapper mapper, KnowledgeProperties knowledge,
                               CodeGraphSemanticProperties properties, MeterRegistry metrics) {
        this.client = client; this.mapper = mapper; this.knowledge = knowledge; this.properties = properties; this.metrics = metrics;
    }

    @Override
    public synchronized void ensureCollection() {
        if (!properties.isEnabled() || initialized.get()) return;
        String collection = properties.getCollection();
        try {
            JsonNode existing = get("/collections/" + collection);
            int size = existing.path("result").path("config").path("params")
                    .path("vectors").path("size").asInt();
            String distance = existing.path("result").path("config").path("params")
                    .path("vectors").path("distance").asText();
            if (size != knowledge.getEmbedding().getDimensions()
                    || !"Cosine".equalsIgnoreCase(distance)) {
                throw new IllegalStateException("Code graph Qdrant collection configuration mismatch");
            }
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
            put("/collections/" + collection,
                    Map.of("vectors", Map.of("size", knowledge.getEmbedding().getDimensions(),
                            "distance", "Cosine")));
        }
        createIndex("bundleId", "integer");
        createIndex("repositorySnapshotId", "integer");
        createIndex("logicalRepositoryKey", "keyword");
        createIndex("commitSha", "keyword");
        createIndex("nodeType", "keyword");
        initialized.set(true);
    }

    @Override
    public void upsertBundle(long bundleId, List<CodeGraphVectorPoint> points) {
        ensureCollection();
        deleteBundle(bundleId);
        if (!properties.isEnabled() || points == null || points.isEmpty()) return;
        for (int from = 0; from < points.size(); from += 256) {
            var batch = points.subList(from, Math.min(points.size(), from + 256));
            List<Map<String, Object>> values = batch.stream()
                    .map(point -> Map.<String, Object>of("id", point.pointId(),
                            "vector", point.vector(), "payload", point.payload()))
                    .toList();
            put("/collections/" + collection() + "/points?wait=true", Map.of("points", values));
        }
    }

    @Override
    public void deleteBundle(long bundleId) {
        ensureCollection();
        if (!properties.isEnabled()) return;
        post("/collections/" + collection() + "/points/delete?wait=true",
                Map.of("filter", Map.of("must", List.of(
                        Map.of("key", "bundleId", "match", Map.of("value", bundleId))))));
    }

    @Override
    public List<CodeGraphVectorSearchHit> search(float[] queryVector,
                                                  CodeGraphVectorSearchFilter filter, int limit) {
        ensureCollection();
        if (!properties.isEnabled() || queryVector == null || limit <= 0) return List.of();
        List<Map<String, Object>> must = new ArrayList<>();
        if (filter != null) {
            addExact(must, "bundleId", filter.bundleId());
            addExact(must, "repositorySnapshotId", filter.repositorySnapshotId());
            addExact(must, "logicalRepositoryKey", filter.logicalRepositoryKey());
            addExact(must, "commitSha", filter.commitSha());
            if (filter.nodeTypes() != null && !filter.nodeTypes().isEmpty()) {
                must.add(Map.of("key", "nodeType", "match",
                        Map.of("any", filter.nodeTypes())));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryVector);
        if (!must.isEmpty()) body.put("filter", Map.of("must", must));
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);
        JsonNode response = post("/collections/" + collection() + "/points/query", body);
        List<CodeGraphVectorSearchHit> hits = new ArrayList<>();
        for (JsonNode point : response.path("result").path("points")) {
            Map<String, Object> payload = mapper.convertValue(point.path("payload"),
                    new TypeReference<>() {});
            hits.add(new CodeGraphVectorSearchHit(point.path("id").asText(),
                    point.path("score").asDouble(), payload));
        }
        return hits;
    }

    private void addExact(List<Map<String, Object>> must, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            must.add(Map.of("key", key, "match", Map.of("value", value)));
        }
    }

    private void createIndex(String field, String schema) {
        put("/collections/" + collection() + "/index",
                Map.of("field_name", field, "field_schema", schema));
    }

    private String collection() { return properties.getCollection(); }
    private JsonNode get(String uri) { return request(HttpMethod.GET, uri, null); }
    private JsonNode post(String uri, Object body) { return request(HttpMethod.POST, uri, body); }
    private JsonNode put(String uri, Object body) { return request(HttpMethod.PUT, uri, body); }

    private JsonNode request(HttpMethod method, String uri, Object body) {
        long started = System.nanoTime();
        try {
            var spec = client.method(method).uri(uri);
            String key = knowledge.getQdrant().getApiKey();
            if (key != null && !key.isBlank()) spec.header("api-key", key);
            JsonNode response = body == null ? spec.retrieve().body(JsonNode.class)
                    : spec.body(body).retrieve().body(JsonNode.class);
            if (response == null) throw new IllegalStateException("Empty Qdrant response");
            return response;
        } finally {
            metrics.timer("code_graph.qdrant.duration")
                    .record(java.time.Duration.ofNanos(System.nanoTime() - started));
        }
    }
}

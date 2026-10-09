package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorPoint;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphSemanticProperties;
import com.company.skillplatform.knowledge.domain.EmbeddingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executor;

@Service
public class CodeGraphSemanticIndexService implements CodeGraphSemanticIndexTrigger {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphSemanticIndexService.class);
    private static final int PAGE_SIZE = 500;
    private static final int STALE_INDEXING_MINUTES = 15;
    private final CodeGraphSemanticCatalog catalog;
    private final CodeGraphEnginePort engine;
    private final EmbeddingPort embedding;
    private final CodeGraphVectorStorePort vectors;
    private final CodeGraphSemanticProperties properties;
    private final Executor recoveryExecutor;

    public CodeGraphSemanticIndexService(CodeGraphSemanticCatalog catalog, CodeGraphEnginePort engine,
                                         EmbeddingPort embedding, CodeGraphVectorStorePort vectors,
                                         CodeGraphSemanticProperties properties) {
        this(catalog, engine, embedding, vectors, properties, Runnable::run);
    }

    @Autowired
    public CodeGraphSemanticIndexService(CodeGraphSemanticCatalog catalog, CodeGraphEnginePort engine,
                                         EmbeddingPort embedding, CodeGraphVectorStorePort vectors,
                                         CodeGraphSemanticProperties properties,
                                         @Qualifier("codeGraphSemanticExecutor") Executor recoveryExecutor) {
        this.catalog = catalog; this.engine = engine; this.embedding = embedding; this.vectors = vectors; this.properties = properties;
        this.recoveryExecutor = recoveryExecutor;
    }

    @Override
    @Async("codeGraphSemanticExecutor")
    public void requestForJob(long generationJobId) {
        var target = catalog.findByGenerationJob(generationJobId);
        if (target.isEmpty()) return;
        var value = target.get();
        if (!properties.isEnabled()) { catalog.updateStatus(value.bindingId(), "DISABLED", null); return; }
        if (!catalog.tryMarkIndexing(value.bindingId())) {
            log.info("event=code_graph.semantic_index.skip jobId={} bindingId={} reason=already_indexing",
                    generationJobId, value.bindingId());
            return;
        }
        try {
            var items = export(value);
            var texts = items.stream().map(this::semanticText).toList();
            var embeddings = embedding.embedDocuments(texts);
            if (embeddings.size() != items.size()) throw new IllegalStateException("Embedding result count mismatch");
            var repositories = new HashMap<String, CodeGraphSemanticCatalog.RepositorySnapshot>();
            value.repositories().forEach(repository -> repositories.put(repository.alias(), repository));
            var points = new ArrayList<CodeGraphVectorPoint>(items.size());
            for (int i = 0; i < items.size(); i++) points.add(point(value, repositories, items.get(i), embeddings.get(i)));
            vectors.upsertBundle(value.bundleId(), points);
            catalog.updateStatus(value.bindingId(), "READY", null);
        } catch (RuntimeException failure) {
            log.warn("event=code_graph.semantic_index.degraded jobId={} bindingId={} errorType={} message={}",
                    generationJobId, value.bindingId(), failure.getClass().getSimpleName(), failure.getMessage(), failure);
            catalog.updateStatus(value.bindingId(), "DEGRADED", "SEMANTIC_INDEX_FAILED");
        }
    }

    /**
     * Requeues bindings that were left in INDEXING by a previous crash or shutdown.
     * Also back-fills bindings whose structural graph is READY but whose semantic
     * index status is still DISABLED — these predate the M6 sidecar and were never
     * given a first build. Runs once at startup; the {@code codeGraphSemanticExecutor}
     * then drains the work.
     */
    @PostConstruct
    void recoverStaleIndexing() {
        log.info("event=code_graph.semantic_index.recover_start enabled={}", properties.isEnabled());
        if (!properties.isEnabled()) return;
        try {
            var stale = catalog.findStaleIndexing(STALE_INDEXING_MINUTES);
            if (!stale.isEmpty()) {
                log.info("event=code_graph.semantic_index.recover_stale count={}", stale.size());
                for (long bindingId : stale) enqueueRecovery(bindingId, "SEMANTIC_INDEX_STALE_RECOVERY");
            }
            var backfill = catalog.findDisabledReadyBindings();
            if (!backfill.isEmpty()) {
                log.info("event=code_graph.semantic_index.backfill count={}", backfill.size());
                for (long bindingId : backfill) enqueueRecovery(bindingId, null);
            }
        } catch (RuntimeException failure) {
            log.warn("event=code_graph.semantic_index.recover_scan_failed errorType={}", failure.getClass().getSimpleName());
        }
    }

    private void enqueueRecovery(long bindingId, String statusBefore) {
        try { recoveryExecutor.execute(() -> retrigger(bindingId, statusBefore)); }
        catch (RuntimeException rejected) {
            log.warn("event=code_graph.semantic_index.recover_rejected bindingId={} errorType={}",
                    bindingId, rejected.getClass().getSimpleName());
        }
    }

    private void retrigger(long bindingId, String statusBefore) {
        try {
            var jobId = catalog.findLatestReadyJobForBinding(bindingId);
            if (jobId.isEmpty()) {
                log.warn("event=code_graph.semantic_index.recover_no_job bindingId={}", bindingId);
                catalog.updateStatus(bindingId, "DEGRADED", "SEMANTIC_INDEX_STALE_NO_JOB");
                return;
            }
            if (statusBefore != null) catalog.updateStatus(bindingId, "DEGRADED", statusBefore);
            requestForJob(jobId.getAsLong());
        } catch (RuntimeException failure) {
            log.warn("event=code_graph.semantic_index.recover_failed bindingId={} errorType={}",
                    bindingId, failure.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> export(CodeGraphSemanticCatalog.IndexTarget target) {
        var result = new ArrayList<Map<String, Object>>();
        for (var repository : target.repositories()) {
            String cursor = null;
            do {
                var parameters = new LinkedHashMap<String, Object>();
                parameters.put("nodeTypes", List.of("SYMBOL", "API", "PROCESS", "COMMUNITY"));
                parameters.put("limit", PAGE_SIZE); if (cursor != null) parameters.put("cursor", cursor);
                log.info("event=code_graph.semantic_index.export_request bindingId={} repository={} artifactUri={}",
                        target.bindingId(), repository.alias(), target.graph().artifactUri());
                var response = engine.query(new CodeGraphEnginePort.QueryRequest("semantic-export", target.graph(), repository.alias(), parameters)).data();
                Object rawItems = response.get("items");
                if (!(rawItems instanceof List<?> page)) throw new IllegalStateException("Semantic export items missing");
                for (Object raw : page) if (raw instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
                cursor = response.get("nextCursor") instanceof String next && !next.isBlank() ? next : null;
                if (Boolean.TRUE.equals(response.get("hasMore")) && cursor == null) throw new IllegalStateException("Semantic export cursor missing");
            } while (cursor != null);
        }
        return result;
    }

    private CodeGraphVectorPoint point(CodeGraphSemanticCatalog.IndexTarget target,
                                       Map<String, CodeGraphSemanticCatalog.RepositorySnapshot> repositories,
                                       Map<String, Object> item, float[] vector) {
        var alias = required(item, "repository"); var repository = repositories.get(alias);
        if (repository == null) throw new IllegalStateException("Semantic node repository is outside bundle");
        var uid = required(item, "nodeUid"); var payload = new LinkedHashMap<String, Object>();
        payload.put("bundleId", target.bundleId()); payload.put("repositorySnapshotId", repository.snapshotId());
        payload.put("logicalRepositoryKey", repository.logicalRepositoryKey()); payload.put("repository", alias);
        payload.put("commitSha", repository.commitSha()); payload.put("nodeUid", uid);
        for (String key : List.of("nodeType", "name", "qualifiedName", "filePath", "language", "summary", "startLine", "endLine"))
            if (item.get(key) != null) payload.put(key, item.get(key));
        return new CodeGraphVectorPoint(stableId(target.bundleId(), repository.snapshotId(), uid), vector, payload);
    }

    private String semanticText(Map<String, Object> item) {
        return String.join(" | ", List.of(required(item, "nodeType"), required(item, "name"),
                text(item, "qualifiedName"), text(item, "filePath"), text(item, "summary")));
    }
    private String required(Map<String, Object> item, String key) { var value = text(item, key); if (value.isBlank()) throw new IllegalStateException(key + " missing"); return value; }
    private String text(Map<String, Object> item, String key) { return item.get(key) == null ? "" : String.valueOf(item.get(key)); }

    static String stableId(long bundleId, long snapshotId, String nodeUid) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((bundleId + ":" + snapshotId + ":" + nodeUid).getBytes(StandardCharsets.UTF_8));
            var bytes = ByteBuffer.wrap(digest); long most = bytes.getLong(), least = bytes.getLong();
            most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
            least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(most, least).toString();
        } catch (Exception failure) { throw new IllegalStateException("Unable to derive code graph point id", failure); }
    }
}

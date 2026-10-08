package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.infrastructure.CodeGraphSemanticProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkerClient.CodeGraphWorkerException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Produces a small, non-blocking code context manifest for one immutable AgentRun binding. */
@Service
public class CodeGraphContextService {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphContextService.class);
    private static final int DEFAULT_MAX_FACTS = 15;
    private static final int DEFAULT_MAX_CHARACTERS = 8_000;
    private final CodeGraphQueryService queries;
    private final ObjectMapper mapper;
    private final CodeGraphSemanticSearchService semanticSearch;
    private final CodeGraphSemanticCatalog semanticCatalog;
    private final CodeGraphSemanticProperties semanticProperties;

    /** Backwards-compatible constructor for tests; semantic catalog and properties may be absent. */
    public CodeGraphContextService(CodeGraphQueryService queries, ObjectMapper mapper) {
        this(queries, mapper, null, null, null);
    }

    public CodeGraphContextService(CodeGraphQueryService queries, ObjectMapper mapper,
                                   CodeGraphSemanticSearchService semanticSearch) {
        this(queries, mapper, semanticSearch, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CodeGraphContextService(CodeGraphQueryService queries, ObjectMapper mapper,
                                   CodeGraphSemanticSearchService semanticSearch,
                                   CodeGraphSemanticCatalog semanticCatalog,
                                   CodeGraphSemanticProperties semanticProperties) {
        this.queries = queries; this.mapper = mapper; this.semanticSearch = semanticSearch;
        this.semanticCatalog = semanticCatalog; this.semanticProperties = semanticProperties;
    }

    public Map<String, Object> preload(long agentRunId, long actorId, String initialRequest, String stage,
                                       String role, List<?> upstreamArtifacts) {
        try {
            var graph = queries.describeForAgent(agentRunId, actorId);
            int maxFacts = maxFacts();
            int maxCharacters = maxCharacters();
            var facts = new ArrayList<Map<String, Object>>();
            var seen = new LinkedHashSet<String>();
            var focus = focus(initialRequest, stage, role, upstreamArtifacts);
            if (semanticSearch != null && "READY".equals(graph.semanticIndexStatus())) {
                try {
                    var roots = semanticSearch.recall(graph.bundleId(), focus, stage);
                    for (var root : roots) {
                        var rootFact = fact(root.payload(), String.valueOf(root.payload().getOrDefault("repository", "")));
                        appendFact(rootFact, facts, seen, maxFacts, maxCharacters);
                    }
                    for (var root : roots) {
                        if (facts.size() >= maxFacts) break;
                        var payload = root.payload(); var uid = payload.get("nodeUid");
                        var repository = String.valueOf(payload.getOrDefault("repository", ""));
                        if (uid == null || repository.isBlank()) continue;
                        var context = queries.executeForAgent(agentRunId, actorId, "context", repository,
                                Map.of("target", Map.of("uid", uid), "limit", maxFacts - facts.size()));
                        appendSymbols(context.data().get("symbols"), repository, facts, seen, maxFacts, maxCharacters);
                    }
                } catch (RuntimeException semanticFailure) {
                    log.warn("event=code_graph.semantic_preload.fallback agentRunId={} bindingId={} errorType={}",
                            agentRunId, graph.bindingId(), semanticFailure.getClass().getSimpleName());
                    markSemanticDegraded(graph.bindingId());
                    facts.clear(); seen.clear();
                }
            }
            for (String repository : facts.isEmpty() ? graph.repositories() : List.<String>of()) {
                if (facts.size() >= maxFacts) break;
                var result = queries.executeForAgent(agentRunId, actorId, "query", repository,
                        Map.of("query", focus, "limit", maxFacts - facts.size()));
                Object symbols = result.data().get("symbols");
                if (!(symbols instanceof List<?> list)) continue;
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> symbol)) continue;
                    var fact = fact(symbol, repository);
                    String key = fact.get("uid") == null
                            ? String.join(":", repository, String.valueOf(fact.get("filePath")), String.valueOf(fact.get("name")), String.valueOf(fact.get("startLine")))
                            : String.valueOf(fact.get("uid"));
                    if (seen.add(key) && withinBudget(facts, fact, maxCharacters)) facts.add(fact);
                    if (facts.size() >= maxFacts) break;
                }
                if (!facts.isEmpty() && facts.size() < maxFacts && facts.get(0).get("uid") != null) {
                    var context = queries.executeForAgent(agentRunId, actorId, "context", repository,
                            Map.of("target", Map.of("uid", facts.get(0).get("uid")),
                                    "limit", maxFacts - facts.size()));
                    if (context != null) appendSymbols(context.data().get("symbols"), repository, facts, seen, maxFacts, maxCharacters);
                }
            }
            var manifest = base(graph);
            manifest.put("status", "READY");
            manifest.put("queryFocus", focus);
            manifest.put("facts", facts);
            manifest.put("factCount", facts.size());
            manifest.put("truncated", facts.size() >= maxFacts);
            return manifest;
        } catch (RuntimeException unavailable) {
            String reason = unavailable instanceof BusinessException business ? business.getCode()
                    : unavailable instanceof CodeGraphWorkerException worker ? worker.code()
                    : "CODE_GRAPH_PRELOAD_FAILED";
            log.warn("event=code_graph.preload.degraded agentRunId={} actorId={} reasonCode={} errorType={}",
                    agentRunId, actorId, reason, unavailable.getClass().getSimpleName());
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("status", "DEGRADED");
            manifest.put("facts", List.of());
            manifest.put("factCount", 0);
            manifest.put("retryable", true);
            manifest.put("degradedReasonCode", reason);
            return manifest;
        }
    }

    /** Marks the binding DEGRADED only when it is currently READY, so we never stomp on a fresh INDEXING cycle. */
    private void markSemanticDegraded(long bindingId) {
        if (semanticCatalog == null) return;
        try {
            semanticCatalog.markDegradedIfReady(bindingId, "SEMANTIC_RECALL_FAILED");
        } catch (RuntimeException failure) {
            log.warn("event=code_graph.semantic_preload.degraded_mark_failed bindingId={} errorType={}",
                    bindingId, failure.getClass().getSimpleName());
        }
    }

    private int maxFacts() {
        return semanticProperties == null ? DEFAULT_MAX_FACTS : Math.max(1, semanticProperties.getPreloadMaxItems());
    }

    private int maxCharacters() {
        return semanticProperties == null ? DEFAULT_MAX_CHARACTERS : Math.max(256, semanticProperties.getPreloadMaxChars());
    }

    private LinkedHashMap<String, Object> base(CodeGraphQueryService.AgentGraphInfo graph) {
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("workflowRunId", graph.workflowRunId());
        manifest.put("bindingId", graph.bindingId());
        manifest.put("bindingVersion", graph.bindingVersion());
        manifest.put("engineType", graph.engineType());
        manifest.put("repositories", graph.repositories());
        return manifest;
    }

    private String focus(String request, String stage, String role, List<?> upstream) {
        var suffix = switch (stage == null ? "" : stage.toUpperCase()) {
            case "ARCHITECTURE" -> " controller service dispatcher domain integration dependency";
            case "UI", "UI_DESIGN" -> " route page component api interaction state";
            case "PRODUCT" -> " user flow capability workflow api";
            default -> " workflow entry requirement api service";
        };
        String value = String.join(" ", request == null ? "" : request,
                stage == null ? "" : stage, role == null ? "" : role, suffix,
                upstreamSummary(upstream)).trim();
        return value.substring(0, Math.min(value.length(), 500));
    }

    private String upstreamSummary(List<?> upstream) {
        if (upstream == null || upstream.isEmpty()) return "";
        try {
            String value = mapper.writeValueAsString(upstream);
            return value.substring(0, Math.min(value.length(), 200));
        } catch (Exception ignored) { return ""; }
    }

    private Map<String, Object> fact(Map<?, ?> source, String repository) {
        var fact = new LinkedHashMap<String, Object>();
        copy(fact, source, "uid"); copy(fact, source, "name"); copy(fact, source, "kind");
        if (!fact.containsKey("uid") && source.get("nodeUid") != null) fact.put("uid", source.get("nodeUid"));
        if (!fact.containsKey("kind") && source.get("nodeType") != null) fact.put("kind", source.get("nodeType"));
        fact.put("repository", source.get("repository") == null ? repository : source.get("repository"));
        copy(fact, source, "qualifiedName"); copy(fact, source, "filePath");
        copy(fact, source, "startLine"); copy(fact, source, "endLine");
        return fact;
    }

    private void appendSymbols(Object value, String repository, List<Map<String, Object>> facts, LinkedHashSet<String> seen,
                               int maxFacts, int maxCharacters) {
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> symbol) || facts.size() >= maxFacts) continue;
            var fact = fact(symbol, repository);
            appendFact(fact, facts, seen, maxFacts, maxCharacters);
        }
    }

    private void appendFact(Map<String, Object> fact, List<Map<String, Object>> facts, LinkedHashSet<String> seen,
                            int maxFacts, int maxCharacters) {
        String key = fact.get("uid") == null
                ? String.join(":", String.valueOf(fact.get("repository")), String.valueOf(fact.get("filePath")), String.valueOf(fact.get("name")), String.valueOf(fact.get("startLine")))
                : String.valueOf(fact.get("uid"));
        if (seen.add(key) && withinBudget(facts, fact, maxCharacters)) facts.add(fact);
    }

    private void copy(Map<String, Object> target, Map<?, ?> source, String key) {
        if (source.get(key) != null) target.put(key, source.get(key));
    }

    private boolean withinBudget(List<Map<String, Object>> existing, Map<String, Object> candidate, int maxCharacters) {
        try {
            var copy = new ArrayList<>(existing); copy.add(candidate);
            return mapper.writeValueAsString(copy).length() <= maxCharacters;
        } catch (Exception ignored) { return false; }
    }
}

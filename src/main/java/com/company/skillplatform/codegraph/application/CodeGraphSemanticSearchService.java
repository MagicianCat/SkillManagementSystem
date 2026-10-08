package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorSearchFilter;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorSearchHit;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphSemanticProperties;
import com.company.skillplatform.knowledge.domain.EmbeddingPort;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CodeGraphSemanticSearchService {
    /**
     * Boosts applied on top of the raw vector score. Kept small so semantic
     * similarity still dominates; the boost only breaks near-ties in favor of
     * symbols from a repository whose logical key matches the active stage.
     */
    private static final double STAGE_TYPE_BOOST = 0.10;
    private static final double REPOSITORY_MATCH_BOOST = 0.08;
    private static final double DEFAULT_TYPE_BOOST = 0.06;

    private final EmbeddingPort embedding;
    private final CodeGraphVectorStorePort vectors;
    private final CodeGraphSemanticProperties properties;

    public CodeGraphSemanticSearchService(EmbeddingPort embedding, CodeGraphVectorStorePort vectors, CodeGraphSemanticProperties properties) {
        this.embedding = embedding; this.vectors = vectors; this.properties = properties;
    }

    public List<CodeGraphVectorSearchHit> recall(long bundleId, String query, String stage) {
        var hits = vectors.search(embedding.embedQuery(query), new CodeGraphVectorSearchFilter(bundleId, null, null, null, Set.of()), properties.getTopK());
        return hits.stream().sorted(Comparator.comparingDouble(hit -> -boosted(hit, stage))).limit(8).toList();
    }

    private double boosted(CodeGraphVectorSearchHit hit, String stage) {
        var type = stringField(hit, "nodeType");
        var logicalKey = stringField(hit, "logicalRepositoryKey");
        var alias = stringField(hit, "repository");
        var normalized = stage == null ? "" : stage.toUpperCase(Locale.ROOT);
        double boost = 0;
        if ("UI".equals(normalized) || "UI_DESIGN".equals(normalized)) {
            if (Set.of("API", "ROUTE", "FUNCTION", "COMPONENT").contains(type)) boost += STAGE_TYPE_BOOST;
            if (matchesAny(logicalKey, alias, "frontend", "web", "ui", "webapp")) boost += REPOSITORY_MATCH_BOOST;
        } else if ("ARCHITECTURE".equals(normalized)) {
            if (Set.of("CLASS", "INTERFACE", "MODULE", "PROCESS").contains(type)) boost += STAGE_TYPE_BOOST;
            if (matchesAny(logicalKey, alias, "backend", "service", "server", "api")) boost += REPOSITORY_MATCH_BOOST;
        } else if ("PRODUCT".equals(normalized)) {
            if (Set.of("API", "ROUTE", "PROCESS", "COMMUNITY").contains(type)) boost += STAGE_TYPE_BOOST;
        } else if (Set.of("API", "ROUTE", "PROCESS").contains(type)) boost += DEFAULT_TYPE_BOOST;
        return hit.vectorScore() + boost;
    }

    /**
     * Matches against the structured {@code logicalRepositoryKey} first, falling back
     * to the bundle alias. Comparison is exact (case-insensitive) or on a
     * hyphen/underscore token boundary — never a bare substring — so keys like
     * "frontend" and "frontier" are not confused.
     */
    private boolean matchesAny(String logicalKey, String alias, String... candidates) {
        return matches(logicalKey, candidates) || matches(alias, candidates);
    }

    private boolean matches(String value, String... candidates) {
        if (value == null || value.isBlank()) return false;
        var normalized = value.toLowerCase(Locale.ROOT);
        for (var candidate : candidates) {
            if (normalized.equals(candidate)) return true;
            // Token-boundary match: split on common separators so
            // "sms-backend" matches "backend" but "backendless" does not.
            for (var token : normalized.split("[-_./\\s]+")) {
                if (token.equals(candidate)) return true;
            }
        }
        return false;
    }

    private String stringField(CodeGraphVectorSearchHit hit, String key) {
        var value = hit.payload().get(key);
        return value == null ? "" : String.valueOf(value);
    }
}

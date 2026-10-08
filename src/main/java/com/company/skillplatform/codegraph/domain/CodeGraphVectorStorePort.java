package com.company.skillplatform.codegraph.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Semantic sidecar storage for code-graph nodes. Structural relationships remain in GitNexus. */
public interface CodeGraphVectorStorePort {
    void ensureCollection();

    /** Replaces all semantic points belonging to a bundle (idempotent). */
    void upsertBundle(long bundleId, List<CodeGraphVectorPoint> points);

    void deleteBundle(long bundleId);

    List<CodeGraphVectorSearchHit> search(float[] queryVector, CodeGraphVectorSearchFilter filter, int limit);

    record CodeGraphVectorPoint(String pointId, float[] vector, Map<String, Object> payload) {}

    record CodeGraphVectorSearchFilter(Long bundleId, Long repositorySnapshotId,
                                       String logicalRepositoryKey, String commitSha,
                                       Set<String> nodeTypes) {
        public CodeGraphVectorSearchFilter {
            nodeTypes = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
        }
    }

    record CodeGraphVectorSearchHit(String pointId, double vectorScore, Map<String, Object> payload) {}
}

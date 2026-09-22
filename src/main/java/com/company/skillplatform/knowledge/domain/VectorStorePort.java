package com.company.skillplatform.knowledge.domain;

import java.util.List;
import java.util.Set;

public interface VectorStorePort {
    void ensureCollection();
    void replaceDocument(long documentId, List<KnowledgeVectorPoint> points);
    void deleteDocument(long documentId);
    List<VectorSearchHit> search(float[] queryVector, KnowledgeFilter filter, int limit);
    IndexedDocumentState documentState(long documentId);
    Set<Long> indexedDocumentIds();

    record KnowledgeFilter(List<Long> visibleTeamIds, boolean platformOnly) {}
    record VectorSearchHit(String pointId, double vectorScore, java.util.Map<String, Object> payload) {}
    record IndexedDocumentState(int revisionNo, String contentSha256, Long teamId, boolean platformVisible,
                                List<String> skillKeys, List<String> developmentStages) {}
}

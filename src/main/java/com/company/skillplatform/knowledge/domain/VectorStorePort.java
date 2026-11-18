package com.company.skillplatform.knowledge.domain;

import java.util.List;
import java.util.Set;

public interface VectorStorePort {
    void ensureCollection();
    void replaceDocument(long documentId, List<KnowledgeVectorPoint> points);
    void deleteDocument(long documentId);
    List<VectorSearchHit> search(float[] queryVector, KnowledgeFilter filter, int limit);
    IndexedDocumentState documentState(long documentId);
    default IndexedDocumentState documentState(long documentId, String audience) { return documentState(documentId); }
    default void setKnowledgeCategory(long documentId, String audience, String category) { throw new UnsupportedOperationException("Category payload updates unavailable"); }
    Set<Long> indexedDocumentIds();

    record KnowledgeFilter(List<Long> visibleTeamIds, boolean platformOnly, String knowledgeCategory) {
        public KnowledgeFilter(List<Long> visibleTeamIds, boolean platformOnly) { this(visibleTeamIds, platformOnly, null); }
    }
    record VectorSearchHit(String pointId, double vectorScore, java.util.Map<String, Object> payload) {}
    record IndexedDocumentState(int revisionNo, String contentSha256, Long teamId, boolean platformVisible,
                                List<String> skillKeys, List<String> developmentStages, String knowledgeCategory) {
        public IndexedDocumentState(int revisionNo, String contentSha256, Long teamId, boolean platformVisible,
                List<String> skillKeys, List<String> developmentStages) {
            this(revisionNo, contentSha256, teamId, platformVisible, skillKeys, developmentStages, null);
        }
    }
}

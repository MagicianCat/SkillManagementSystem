package com.company.skillplatform.knowledge.domain;

import java.util.List;
import java.util.Set;

public interface VectorStorePort {
    default void replaceDocument(long id, List<KnowledgeVectorPoint> points, java.util.function.Supplier<ExternalRoleMetadata> metadata) {
        var current = metadata.get();
        if (current == null) { deleteDocument(id); return; }
        var payload = current.toPayload();
        points.forEach(point -> point.payload().putAll(payload));
        replaceDocument(id, points);
    }
    default boolean syncExternalRoleMetadata(long id, java.util.function.Supplier<ExternalRoleMetadata> metadata) {
        throw new UnsupportedOperationException("Role metadata updates unavailable");
    }
    default boolean hasStaleRoleMetadata(long id, ExternalRoleMetadata metadata) {
        throw new UnsupportedOperationException("Role metadata checks unavailable");
    }
    void ensureCollection();
    void replaceDocument(long documentId, List<KnowledgeVectorPoint> points);
    void deleteDocument(long documentId);
    List<VectorSearchHit> search(float[] queryVector, KnowledgeFilter filter, int limit);
    IndexedDocumentState documentState(long documentId);
    default IndexedDocumentState documentState(long documentId, String audience) { return documentState(documentId); }
    default void setKnowledgeCategory(long documentId, String audience, String category) { throw new UnsupportedOperationException("Category payload updates unavailable"); }
    Set<Long> indexedDocumentIds();

    record KnowledgeFilter(List<Long> visibleTeamIds, boolean platformOnly, String knowledgeCategory, List<Long> roleIds, boolean generalOnly, String resourceType) {
        public KnowledgeFilter(List<Long> visibleTeamIds, boolean platformOnly, String knowledgeCategory,
                List<Long> roleIds, boolean generalOnly) {
            this(visibleTeamIds,platformOnly,knowledgeCategory,roleIds,generalOnly,null);
        }
        public KnowledgeFilter(List<Long> visibleTeamIds, boolean platformOnly, String knowledgeCategory) {
            this(visibleTeamIds,platformOnly,knowledgeCategory,List.of(),false);
        }
        public KnowledgeFilter(List<Long> visibleTeamIds, boolean platformOnly) { this(visibleTeamIds, platformOnly, null); }
    }
    record VectorSearchHit(String pointId, double vectorScore, java.util.Map<String, Object> payload) {}
    record IndexedDocumentState(int revisionNo, String contentSha256, Long teamId, boolean platformVisible,
                                List<String> skillKeys, List<String> developmentStages, String knowledgeCategory,
                                Integer roleMetadataSchemaVersion, String roleMetadataFingerprint) {
        public IndexedDocumentState(int revisionNo, String contentSha256, Long teamId, boolean platformVisible,
                List<String> skillKeys, List<String> developmentStages, String knowledgeCategory) {
            this(revisionNo, contentSha256, teamId, platformVisible, skillKeys, developmentStages, knowledgeCategory, null, null);
        }
        public IndexedDocumentState(int revisionNo, String contentSha256, Long teamId, boolean platformVisible,
                List<String> skillKeys, List<String> developmentStages) {
            this(revisionNo, contentSha256, teamId, platformVisible, skillKeys, developmentStages, null);
        }
    }
}

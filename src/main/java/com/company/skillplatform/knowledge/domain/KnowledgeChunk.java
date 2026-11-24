package com.company.skillplatform.knowledge.domain;

import java.util.List;

public record KnowledgeChunk(Long documentId, int revisionNo, String contentSha256, String title,
        Long teamId, boolean platformVisible, String status, List<String> skillKeys,
        List<String> developmentStages, int chunkIndex, String heading, List<String> headingPath,
        String content, String embeddingText, String knowledgeCategory, ExternalRoleMetadata externalRoleMetadata) {
    public KnowledgeChunk(Long documentId, int revisionNo, String contentSha256, String title,
        Long teamId, boolean platformVisible, String status, List<String> skillKeys,
        List<String> developmentStages, int chunkIndex, String heading, List<String> headingPath,
        String content, String embeddingText, String knowledgeCategory) {
        this(documentId, revisionNo, contentSha256, title, teamId, platformVisible, status, skillKeys, developmentStages, chunkIndex, heading, headingPath, content, embeddingText, knowledgeCategory, ExternalRoleMetadata.LOCAL);
    }

}

package com.company.skillplatform.knowledge.domain;

import java.util.List;

public record KnowledgeDocument(Long documentId, int revisionNo, String contentSha256, String title,
        Long teamId, boolean platformVisible, String status, List<String> skillKeys,
        List<String> developmentStages, String markdownContent, String knowledgeCategory, ExternalRoleMetadata externalRoleMetadata) {
    public KnowledgeDocument(Long documentId, int revisionNo, String contentSha256, String title,
        Long teamId, boolean platformVisible, String status, List<String> skillKeys,
        List<String> developmentStages, String markdownContent, String knowledgeCategory) {
        this(documentId, revisionNo, contentSha256, title, teamId, platformVisible, status, skillKeys, developmentStages, markdownContent, knowledgeCategory, ExternalRoleMetadata.LOCAL);
    }

    public KnowledgeDocument(Long documentId, int revisionNo, String contentSha256, String title,
            Long teamId, boolean platformVisible, String status, List<String> skillKeys,
            List<String> developmentStages, String markdownContent) {
        this(documentId, revisionNo, contentSha256, title, teamId, platformVisible, status, skillKeys,
                developmentStages, markdownContent, "OTHER");
    }
}

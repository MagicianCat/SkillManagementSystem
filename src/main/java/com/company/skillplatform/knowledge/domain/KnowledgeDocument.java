package com.company.skillplatform.knowledge.domain;

import java.util.List;

public record KnowledgeDocument(Long documentId, int revisionNo, String contentSha256, String title,
        String documentType, Long teamId, boolean platformVisible, String status, List<String> skillKeys,
        List<String> developmentStages, String markdownContent) {}

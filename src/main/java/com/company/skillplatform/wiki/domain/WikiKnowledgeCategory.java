package com.company.skillplatform.wiki.domain;

import com.company.skillplatform.common.application.BusinessException;
import org.springframework.http.HttpStatus;

public enum WikiKnowledgeCategory {
    SKILL_TOOL, PROJECT_CONTEXT, INTERNAL_KNOWLEDGE, OTHER;

    public static WikiKnowledgeCategory parse(String value, WikiKnowledgeCategory fallback) {
        if (value == null) return fallback;
        try { return valueOf(value); }
        catch (IllegalArgumentException exception) {
            throw new BusinessException("WIKI_KNOWLEDGE_CATEGORY_INVALID", "Invalid Wiki knowledge category", HttpStatus.BAD_REQUEST);
        }
    }
}

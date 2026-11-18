package com.company.skillplatform.wiki.domain;

import com.company.skillplatform.common.application.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiKnowledgeCategoryTest {
    @Test void omittedCategoryDefaultsToOtherButExplicitInvalidValueIsRejected() {
        assertThat(WikiKnowledgeCategory.parse(null, WikiKnowledgeCategory.OTHER)).isEqualTo(WikiKnowledgeCategory.OTHER);
        assertThat(WikiKnowledgeCategory.parse("SKILL_TOOL", WikiKnowledgeCategory.OTHER)).isEqualTo(WikiKnowledgeCategory.SKILL_TOOL);
        assertThatThrownBy(() -> WikiKnowledgeCategory.parse("", WikiKnowledgeCategory.OTHER))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo("WIKI_KNOWLEDGE_CATEGORY_INVALID"));
        assertThatThrownBy(() -> WikiKnowledgeCategory.parse("UNKNOWN", WikiKnowledgeCategory.OTHER))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo("WIKI_KNOWLEDGE_CATEGORY_INVALID"));
    }
}

package com.company.skillplatform.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownHeadingChunkerTest {
    @Test
    void preservesHeadingPathAndDocumentMetadata() {
        var chunker = new MarkdownHeadingChunker(80, 10);

        var chunks = chunker.chunk(new KnowledgeDocument(12L, 3, "hash", "研发全流程最佳实践",
                "SKILL_GUIDE", 7L, false, "ACTIVE", List.of("java-backend"),
                List.of("BACKEND_CODING"), "# 后端开发\n## 单元测试\n应先补齐单元测试。"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("单元测试");
        assertThat(chunks.get(0).headingPath()).containsExactly("后端开发", "单元测试");
        assertThat(chunks.get(0).embeddingText()).contains("研发全流程最佳实践", "后端开发 > 单元测试", "应先补齐单元测试");
        assertThat(chunks.get(0).skillKeys()).containsExactly("java-backend");
    }

    @Test
    void splitsLongSectionsWithOverlapWithoutEmittingBlankChunks() {
        var chunker = new MarkdownHeadingChunker(30, 6);
        String content = "# 标题\n" + "甲".repeat(22) + "乙".repeat(22);

        var chunks = chunker.chunk(new KnowledgeDocument(1L, 1, "hash", "文档", "SKILL_GUIDE",
                null, true, "ACTIVE", List.of(), List.of(), content));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content()).isNotBlank());
        assertThat(chunks.get(1).content()).startsWith(chunks.get(0).content().substring(chunks.get(0).content().length() - 6));
    }

    @Test
    void pointIdIsStableAcrossRetriesAndChangesAcrossRevision() {
        assertThat(KnowledgeVectorPoint.stableId(3L, 2, 1))
                .isEqualTo(KnowledgeVectorPoint.stableId(3L, 2, 1))
                .isNotEqualTo(KnowledgeVectorPoint.stableId(3L, 3, 1));
    }
}

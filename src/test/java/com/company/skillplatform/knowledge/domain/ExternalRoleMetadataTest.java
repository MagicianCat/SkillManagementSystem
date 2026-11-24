package com.company.skillplatform.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalRoleMetadataTest {
    @Test
    void roleChangesAffectOnlyPayloadAndLeaveEmbeddingInputAndPointIdentityStable() {
        var role = new ExternalRoleMetadata.Role(12, "外部角色", "ROLE-001");
        var metadata = new ExternalRoleMetadata("source-a", "DOC-001", "DOCUMENT", List.of(role));
        var renamed = new ExternalRoleMetadata("source-a", "DOC-001", "DOCUMENT",
                List.of(new ExternalRoleMetadata.Role(12, "新角色名称", "ROLE-001")));
        var chunker = new MarkdownHeadingChunker(500, 50);
        var original = document(metadata);
        var updated = document(renamed);
        var first = chunker.chunk(original).get(0);
        var second = chunker.chunk(updated).get(0);
        assertThat(first.embeddingText()).isEqualTo(second.embeddingText());
        var point = KnowledgeVectorPoint.from(first, new float[]{1});
        var changed = KnowledgeVectorPoint.from(second, new float[]{1});
        assertThat(point.id()).isEqualTo(changed.id());
        assertThat(point.payload()).containsEntry("externalRoleIds", List.of(12L));
        assertThat(changed.payload().get("roleMetadataFingerprint"))
                .isNotEqualTo(point.payload().get("roleMetadataFingerprint"));
        assertThat(ExternalRoleMetadata.LOCAL.toPayload()).containsEntry("externalRoleIds", List.of());
    }

    private KnowledgeDocument document(ExternalRoleMetadata metadata) {
        return new KnowledgeDocument(1L, 1, "hash", "标题", null, true, "ACTIVE", List.of(),
                List.of(), "# 架构\n\n正文内容。", "PROJECT_CONTEXT", metadata);
    }
}

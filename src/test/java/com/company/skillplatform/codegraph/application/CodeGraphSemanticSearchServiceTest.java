package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphSemanticProperties;
import com.company.skillplatform.knowledge.domain.EmbeddingPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeGraphSemanticSearchServiceTest {
    @Test
    void appliesStageBoostAndReturnsBestRoots() {
        var embedding = mock(EmbeddingPort.class); var vectors = mock(CodeGraphVectorStorePort.class);
        when(embedding.embedQuery("设计工作流页面")).thenReturn(new float[]{1});
        when(vectors.search(any(), any(), anyInt())).thenReturn(List.of(
                hit(.91, "METHOD", "backend", "backend", "Method:service"),
                hit(.86, "API", "frontend", "frontend", "Route:workflow")));
        var result = new CodeGraphSemanticSearchService(embedding, vectors, properties())
                .recall(44, "设计工作流页面", "UI");
        assertThat(result).extracting(root -> root.payload().get("nodeUid"))
                .containsExactly("Route:workflow", "Method:service");
    }

    @Test
    void repositoryBoostUsesLogicalKeyNotBareSubstring() {
        var embedding = mock(EmbeddingPort.class); var vectors = mock(CodeGraphVectorStorePort.class);
        when(embedding.embedQuery(anyString())).thenReturn(new float[]{1});
        when(vectors.search(any(), any(), anyInt())).thenReturn(List.of(
                // Bare substring "front" would match "frontier" under the old logic.
                // The new logic must NOT boost "frontier" for a UI stage.
                hit(.90, "API", "frontier", "frontier", "Route:frontier"),
                hit(.89, "API", "frontend", "frontend", "Route:workflow")));

        var result = new CodeGraphSemanticSearchService(embedding, vectors, properties())
                .recall(44, "页面", "UI");

        // frontend (boosted) wins over frontier (not boosted) despite lower raw score.
        assertThat(result).extracting(root -> root.payload().get("nodeUid"))
                .containsExactly("Route:workflow", "Route:frontier");
    }

    @Test
    void repositoryBoostMatchesOnTokenBoundaries() {
        var embedding = mock(EmbeddingPort.class); var vectors = mock(CodeGraphVectorStorePort.class);
        when(embedding.embedQuery(anyString())).thenReturn(new float[]{1});
        when(vectors.search(any(), any(), anyInt())).thenReturn(List.of(
                // "backendless" must NOT match the "backend" candidate.
                hit(.90, "CLASS", "backendless", "backendless", "Class:Fake"),
                // "sms-backend" SHOULD match — token boundary on hyphen.
                hit(.89, "CLASS", "sms-backend", "sms-backend", "Class:Real")));

        var result = new CodeGraphSemanticSearchService(embedding, vectors, properties())
                .recall(44, "workflow", "ARCHITECTURE");

        assertThat(result).extracting(root -> root.payload().get("nodeUid"))
                .containsExactly("Class:Real", "Class:Fake");
    }

    private static CodeGraphVectorStorePort.CodeGraphVectorSearchHit hit(
            double score, String type, String logicalKey, String alias, String uid) {
        return new CodeGraphVectorStorePort.CodeGraphVectorSearchHit(uid, score,
                Map.of("nodeUid", uid, "nodeType", type,
                        "logicalRepositoryKey", logicalKey, "repository", alias,
                        "name", uid));
    }

    private static CodeGraphSemanticProperties properties() {
        var p = new CodeGraphSemanticProperties(); p.setTopK(20); return p;
    }
}

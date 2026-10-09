package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphSemanticProperties;
import com.company.skillplatform.knowledge.domain.EmbeddingPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeGraphSemanticIndexServiceTest {
    @Test
    void exportsEmbedsAndIndexesTheFrozenBundle() {
        var catalog = mock(CodeGraphSemanticCatalog.class);
        var engine = mock(CodeGraphEnginePort.class);
        var embedding = mock(EmbeddingPort.class);
        var vectors = mock(CodeGraphVectorStorePort.class);
        var target = target();
        when(catalog.findByGenerationJob(9)).thenReturn(Optional.of(target));
        when(catalog.tryMarkIndexing(77)).thenReturn(true);
        when(engine.query(any())).thenReturn(new CodeGraphEnginePort.QueryResult("semantic-export", Map.of(
                "items", List.of(Map.of("nodeUid", "Method:a", "nodeType", "METHOD", "name", "start", "repository", "backend", "filePath", "src/A.java", "summary", "starts workflow")),
                "truncated", false)));
        when(embedding.embedDocuments(anyList())).thenReturn(List.of(new float[]{1, 2}));

        new CodeGraphSemanticIndexService(catalog, engine, embedding, vectors, properties()).requestForJob(9);

        @SuppressWarnings("unchecked") var points = (List<CodeGraphVectorStorePort.CodeGraphVectorPoint>)
                mockingDetails(vectors).getInvocations().stream().filter(i -> i.getMethod().getName().equals("upsertBundle"))
                        .findFirst().orElseThrow().getArgument(1);
        assertThat(points).hasSize(1);
        assertThat(points.get(0).pointId()).matches("[0-9a-f-]{36}");
        assertThat(points.get(0).payload()).containsEntry("bundleId", 44L)
                .containsEntry("repositorySnapshotId", 55L).containsEntry("commitSha", "a".repeat(40));
        verify(catalog).updateStatus(77, "READY", null);
    }

    @Test
    void semanticFailureOnlyDegradesTheSidecar() {
        var catalog = mock(CodeGraphSemanticCatalog.class);
        var engine = mock(CodeGraphEnginePort.class);
        when(catalog.findByGenerationJob(9)).thenReturn(Optional.of(target()));
        when(catalog.tryMarkIndexing(77)).thenReturn(true);
        when(engine.query(any())).thenThrow(new IllegalStateException("qdrant or export unavailable"));

        new CodeGraphSemanticIndexService(catalog, engine, mock(EmbeddingPort.class),
                mock(CodeGraphVectorStorePort.class), properties()).requestForJob(9);

        verify(catalog).updateStatus(77, "DEGRADED", "SEMANTIC_INDEX_FAILED");
    }

    @Test
    void skipsWorkWhenBindingIsAlreadyBeingIndexed() {
        var catalog = mock(CodeGraphSemanticCatalog.class);
        var engine = mock(CodeGraphEnginePort.class);
        var embedding = mock(EmbeddingPort.class);
        var vectors = mock(CodeGraphVectorStorePort.class);
        when(catalog.findByGenerationJob(9)).thenReturn(Optional.of(target()));
        when(catalog.tryMarkIndexing(77)).thenReturn(false);

        new CodeGraphSemanticIndexService(catalog, engine, embedding, vectors, properties()).requestForJob(9);

        verifyNoInteractions(engine);
        verifyNoInteractions(embedding);
        verifyNoInteractions(vectors);
        verify(catalog, never()).updateStatus(anyLong(), anyString(), any());
    }

    @Test
    void startupRecoveryQueuesBackfillInsteadOfBlockingTheMainThread() {
        var catalog = mock(CodeGraphSemanticCatalog.class);
        var engine = mock(CodeGraphEnginePort.class);
        var queued = new ArrayList<Runnable>();
        when(catalog.findStaleIndexing(anyInt())).thenReturn(List.of());
        when(catalog.findDisabledReadyBindings()).thenReturn(List.of(77L));
        when(catalog.findLatestReadyJobForBinding(77)).thenReturn(java.util.OptionalLong.of(9));
        when(catalog.findByGenerationJob(9)).thenReturn(Optional.of(target()));
        when(catalog.tryMarkIndexing(77)).thenReturn(false);
        var service = new CodeGraphSemanticIndexService(catalog, engine, mock(EmbeddingPort.class),
                mock(CodeGraphVectorStorePort.class), properties(), queued::add);

        service.recoverStaleIndexing();

        verifyNoInteractions(engine);
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(catalog).findByGenerationJob(9);
    }

    @Test
    void pointIdentityIsIsolatedByBundleEvenWhenSnapshotAndNodeAreReused() {
        assertThat(CodeGraphSemanticIndexService.stableId(44L, 55L, "Method:a"))
                .isNotEqualTo(CodeGraphSemanticIndexService.stableId(45L, 55L, "Method:a"));
        assertThat(CodeGraphSemanticIndexService.stableId(44L, 55L, "Method:a"))
                .isEqualTo(CodeGraphSemanticIndexService.stableId(44L, 55L, "Method:a"));
    }

    private static CodeGraphSemanticCatalog.IndexTarget target() {
        return new CodeGraphSemanticCatalog.IndexTarget(77, 44,
                new CodeGraphEnginePort.GraphRef("file:///bundle.tar.zst", "b".repeat(64),
                        List.of(new CodeGraphEnginePort.GraphRepository("backend", "backend"))),
                List.of(new CodeGraphSemanticCatalog.RepositorySnapshot(55, "backend", "backend", "a".repeat(40))));
    }

    private static CodeGraphSemanticProperties properties() {
        var value = new CodeGraphSemanticProperties(); value.setEnabled(true); return value;
    }
}

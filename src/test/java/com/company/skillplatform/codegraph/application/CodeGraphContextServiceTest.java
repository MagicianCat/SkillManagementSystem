package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.infrastructure.CodeGraphSemanticProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeGraphContextServiceTest {
    @Test
    void buildsBoundedFactsFromTheFrozenAgentBinding() {
        var queries = mock(CodeGraphQueryService.class);
        when(queries.describeForAgent(9L, 7L)).thenReturn(new CodeGraphQueryService.AgentGraphInfo(
                31L, 88L, 2, "GITNEXUS", List.of("backend")));
        when(queries.executeForAgent(eq(9L), eq(7L), eq("query"), eq("backend"), anyMap()))
                .thenReturn(new CodeGraphQueryService.QueryResult(31L, "query", Map.of("symbols", List.of(
                        Map.of("uid", "Class:WorkflowService", "name", "WorkflowService", "kind", "CLASS", "repository", "backend", "filePath", "src/WorkflowService.java", "startLine", 20),
                        Map.of("uid", "Class:WorkflowController", "name", "WorkflowController", "kind", "CLASS", "repository", "backend", "filePath", "src/WorkflowController.java", "startLine", 12)))));

        var manifest = new CodeGraphContextService(queries, new ObjectMapper())
                .preload(9L, 7L, "启动工作流", "ARCHITECTURE", "architect", List.of());

        assertEquals("READY", manifest.get("status"));
        assertEquals(88L, manifest.get("bindingId"));
        assertEquals(2, ((List<?>) manifest.get("facts")).size());
        assertFalse(new ObjectMapper().valueToTree(manifest).toString().contains("artifact"));
    }

    @Test
    void degradesWithoutBlockingAgentStartupWhenGraphQueryFails() {
        var queries = mock(CodeGraphQueryService.class);
        when(queries.describeForAgent(anyLong(), anyLong())).thenThrow(new IllegalStateException("worker unavailable"));

        var manifest = new CodeGraphContextService(queries, new ObjectMapper())
                .preload(9L, 7L, "需求", "REQUIREMENT", "writer", List.of());

        assertEquals("DEGRADED", manifest.get("status"));
        assertTrue(((List<?>) manifest.get("facts")).isEmpty());
        assertFalse(manifest.toString().contains("worker unavailable"));
    }

    @Test
    void readySemanticSidecarRecallsRootsThenExpandsGraphContext() {
        var queries = mock(CodeGraphQueryService.class);
        var semantic = mock(CodeGraphSemanticSearchService.class);
        when(queries.describeForAgent(9L, 7L)).thenReturn(new CodeGraphQueryService.AgentGraphInfo(
                31L, 88L, 2, "GITNEXUS", List.of("frontend"), 44L, "READY"));
        when(semantic.recall(eq(44L), anyString(), eq("UI"))).thenReturn(List.of(
                new com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorSearchHit("p", .9,
                        Map.of("nodeUid", "Route:workflow", "nodeType", "API", "name", "workflow", "repository", "frontend", "filePath", "src/api.ts"))));
        when(queries.executeForAgent(eq(9L), eq(7L), eq("context"), eq("frontend"), anyMap()))
                .thenReturn(new CodeGraphQueryService.QueryResult(31L, "context", Map.of("symbols", List.of(
                        Map.of("uid", "Function:start", "name", "start", "kind", "FUNCTION", "repository", "frontend", "filePath", "src/start.ts")))));

        var manifest = new CodeGraphContextService(queries, new ObjectMapper(), semantic)
                .preload(9L, 7L, "工作流页面", "UI", "designer", List.of());

        assertEquals(2, ((List<?>) manifest.get("facts")).size());
        verify(semantic).recall(eq(44L), anyString(), eq("UI"));
    }

    @Test
    void marksBindingDegradedWhenSemanticRecallFailsAtRuntime() {
        var queries = mock(CodeGraphQueryService.class);
        var semantic = mock(CodeGraphSemanticSearchService.class);
        var catalog = mock(CodeGraphSemanticCatalog.class);
        when(queries.describeForAgent(9L, 7L)).thenReturn(new CodeGraphQueryService.AgentGraphInfo(
                31L, 88L, 2, "GITNEXUS", List.of("backend"), 44L, "READY"));
        when(semantic.recall(eq(44L), anyString(), anyString()))
                .thenThrow(new IllegalStateException("qdrant down"));
        when(queries.executeForAgent(eq(9L), eq(7L), eq("query"), eq("backend"), anyMap()))
                .thenReturn(new CodeGraphQueryService.QueryResult(31L, "query", Map.of("symbols", List.of(
                        Map.of("uid", "Class:WorkflowService", "name", "WorkflowService", "kind", "CLASS", "repository", "backend")))));

        var properties = new CodeGraphSemanticProperties();
        var manifest = new CodeGraphContextService(queries, new ObjectMapper(), semantic, catalog, properties)
                .preload(9L, 7L, "workflow", "ARCHITECTURE", "architect", List.of());

        assertEquals("READY", manifest.get("status"));
        verify(catalog).markDegradedIfReady(88L, "SEMANTIC_RECALL_FAILED");
        // Fell back to the structural query path.
        assertFalse(((List<?>) manifest.get("facts")).isEmpty());
    }

    @Test
    void doesNotMarkDegradedWhenCatalogIsNotWired() {
        var queries = mock(CodeGraphQueryService.class);
        var semantic = mock(CodeGraphSemanticSearchService.class);
        when(queries.describeForAgent(9L, 7L)).thenReturn(new CodeGraphQueryService.AgentGraphInfo(
                31L, 88L, 2, "GITNEXUS", List.of("backend"), 44L, "READY"));
        when(semantic.recall(anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException("qdrant down"));
        when(queries.executeForAgent(eq(9L), eq(7L), eq("query"), eq("backend"), anyMap()))
                .thenReturn(new CodeGraphQueryService.QueryResult(31L, "query", Map.of("symbols", List.of())));

        // Legacy two-arg semantic constructor leaves catalog null — must not throw.
        var manifest = new CodeGraphContextService(queries, new ObjectMapper(), semantic)
                .preload(9L, 7L, "workflow", "ARCHITECTURE", "architect", List.of());

        assertEquals("READY", manifest.get("status"));
    }

    @Test
    void honoursConfiguredMaxFactsAndCharacters() {
        var queries = mock(CodeGraphQueryService.class);
        when(queries.describeForAgent(9L, 7L)).thenReturn(new CodeGraphQueryService.AgentGraphInfo(
                31L, 88L, 2, "GITNEXUS", List.of("backend")));
        when(queries.executeForAgent(eq(9L), eq(7L), eq("query"), eq("backend"), anyMap()))
                .thenReturn(new CodeGraphQueryService.QueryResult(31L, "query", Map.of("symbols", List.of(
                        Map.of("uid", "Class:A", "name", "A", "kind", "CLASS", "repository", "backend"),
                        Map.of("uid", "Class:B", "name", "B", "kind", "CLASS", "repository", "backend"),
                        Map.of("uid", "Class:C", "name", "C", "kind", "CLASS", "repository", "backend")))));

        var properties = new CodeGraphSemanticProperties();
        properties.setPreloadMaxItems(2);
        var manifest = new CodeGraphContextService(queries, new ObjectMapper(), null, null, properties)
                .preload(9L, 7L, "workflow", "ARCHITECTURE", "architect", List.of());

        assertEquals(2, ((List<?>) manifest.get("facts")).size());
        assertTrue((Boolean) manifest.get("truncated"));
    }
}

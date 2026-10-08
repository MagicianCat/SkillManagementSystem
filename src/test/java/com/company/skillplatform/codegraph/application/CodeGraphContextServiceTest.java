package com.company.skillplatform.codegraph.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
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
}

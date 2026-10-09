package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M7 Case 7 — an AgentRun's code-graph queries are pinned to the binding that was
 * ACTIVE at the moment the AgentRun row was created. After a REPO_APPEND swap:
 *
 * <ul>
 *   <li>Old AgentRun (created before the swap) keeps reading v1 even though v1 is now
 *       SUPERSEDED.</li>
 *   <li>New AgentRun (created after the swap) reads v2.</li>
 *   <li>The frozen {@code agent_workflow_run.code_graph_binding_id} always wins; the
 *       "latest ACTIVE" lookup is never re-issued for the agent path.</li>
 * </ul>
 *
 * The JDBC layer is mocked at the SQL boundary; engine responses are mocked at the
 * port. BindingRow/GraphRepository instances are constructed via reflection because
 * the row type is private to {@link CodeGraphQueryService}.
 */
class CodeGraphAgentBindingPinningTest {

    @Test
    void agentRunBeforeSwitchKeepsQueryingV1() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var engine = mock(CodeGraphEnginePort.class);
        var service = new CodeGraphQueryService(jdbc, engine, new ObjectMapper());

        // Agent run frozen on binding 11 (v1). v1 has since been SUPERSEDED by v2 (binding 22).
        stubAgentBinding(jdbc, 1000L, 31L, 11L);
        stubPinnedBindingRow(jdbc, 31L, 11L,
                newBindingRow(11L, 1, 500L, "v1.tar", "a".repeat(64), "READY"));
        stubBundleRepositories(jdbc, 500L, "backend", "frontend");
        stubEngineOverview(engine, "v1.tar", Map.of("nodeCount", 1000));

        var result = service.overviewForAgent(1000L, 7L);

        assertThat(result.bindingId()).isEqualTo(11L);
        assertThat(result.bindingVersion()).isEqualTo(1);
        // Engine was called with v1's artifact, never with v2's.
        verify(engine).query(argThat(q -> "v1.tar".equals(q.graph().artifactUri())));
        verify(engine, never()).query(argThat(q -> "v2.tar".equals(q.graph().artifactUri())));
    }

    @Test
    void agentRunAfterSwitchQueriesV2() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var engine = mock(CodeGraphEnginePort.class);
        var service = new CodeGraphQueryService(jdbc, engine, new ObjectMapper());

        // Agent run created after the swap: frozen binding_id is 22 (v2).
        stubAgentBinding(jdbc, 2000L, 31L, 22L);
        stubPinnedBindingRow(jdbc, 31L, 22L,
                newBindingRow(22L, 2, 600L, "v2.tar", "b".repeat(64), "READY"));
        stubBundleRepositories(jdbc, 600L, "backend", "frontend", "infra");
        stubEngineOverview(engine, "v2.tar", Map.of("nodeCount", 1500));

        var result = service.overviewForAgent(2000L, 7L);

        assertThat(result.bindingId()).isEqualTo(22L);
        assertThat(result.bindingVersion()).isEqualTo(2);
        verify(engine).query(argThat(q -> "v2.tar".equals(q.graph().artifactUri())));
    }

    /**
     * The pinned agent path never falls through to the "latest ACTIVE" lookup, even
     * when such a binding exists. Verify by checking the SQL that resolves "latest
     * ACTIVE" was never issued with the agent's user/run.
     */
    @Test
    void agentPathNeverHitsTheActiveBindingLookup() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var engine = mock(CodeGraphEnginePort.class);
        var service = new CodeGraphQueryService(jdbc, engine, new ObjectMapper());

        stubAgentBinding(jdbc, 1000L, 31L, 11L);
        stubPinnedBindingRow(jdbc, 31L, 11L,
                newBindingRow(11L, 1, 500L, "v1.tar", "a".repeat(64), "READY"));
        stubBundleRepositories(jdbc, 500L, "backend");
        stubEngineOverview(engine, "v1.tar", Map.of());

        service.overviewForAgent(1000L, 7L);

        // The un-pinned resolve query contains "ORDER BY CASE WHEN b.status='ACTIVE'".
        // Verify it was never issued.
        verify(jdbc, never()).query(
                argThat(sql -> sql != null && sql.contains("ORDER BY CASE WHEN b.status")),
                any(RowMapper.class), any(), any());
    }

    @Test
    void agentRunWithNoFrozenBindingFailsFast() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var engine = mock(CodeGraphEnginePort.class);
        var service = new CodeGraphQueryService(jdbc, engine, new ObjectMapper());

        // Agent run exists but has no frozen binding (e.g. workflow not configured).
        stubAgentBinding(jdbc, 3000L, 31L, null);

        assertThatThrownBy(() -> service.overviewForAgent(3000L, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("CODE_GRAPH_NOT_BOUND"));
        verifyNoInteractions(engine);
    }

    // ---------- stubs -------------------------------------------------------

    private void stubAgentBinding(JdbcTemplate jdbc, long agentRunId, long workflowRunId, Long bindingId) throws Exception {
        var agentBindingClass = Class.forName("com.company.skillplatform.codegraph.application.CodeGraphQueryService$AgentBinding");
        Constructor<?> ctor = agentBindingClass.getDeclaredConstructor(long.class, Long.class);
        ctor.setAccessible(true);
        var row = ctor.newInstance(workflowRunId, bindingId);
        when(jdbc.query(
                argThat(sql -> sql != null && sql.startsWith("SELECT sr.workflow_run_id,ar.code_graph_binding_id")),
                any(RowMapper.class), eq(7L), eq(agentRunId)))
                .thenAnswer(invocation -> List.of(row));
    }

    private void stubPinnedBindingRow(JdbcTemplate jdbc, long runId, long pinnedBindingId, Object bindingRow) {
        when(jdbc.query(
                argThat(sql -> sql != null && sql.contains("b.id=?") && sql.contains("JOIN workflow_run_code_graph_binding b")),
                any(RowMapper.class), eq(7L), eq(pinnedBindingId), eq(runId)))
                .thenAnswer(invocation -> List.of(bindingRow));
    }

    private void stubBundleRepositories(JdbcTemplate jdbc, long bundleId, String... aliases) throws Exception {
        var repoClass = Class.forName("com.company.skillplatform.codegraph.domain.CodeGraphEnginePort$GraphRepository");
        Constructor<?> ctor = repoClass.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        var repos = new java.util.ArrayList<>();
        for (String alias : aliases) repos.add(ctor.newInstance(alias, alias));
        when(jdbc.query(
                argThat(sql -> sql != null && sql.startsWith("SELECT repository_alias FROM code_graph_bundle_repository")),
                any(RowMapper.class), eq(bundleId)))
                .thenAnswer(invocation -> repos);
    }

    private void stubEngineOverview(CodeGraphEnginePort engine, String artifactKey, Map<String, Object> data) {
        when(engine.query(argThat(q -> artifactKey.equals(q.graph().artifactUri()))))
                .thenReturn(new CodeGraphEnginePort.QueryResult("overview", data));
    }

    private Object newBindingRow(long bindingId, int version, long bundleId,
                                 String artifactKey, String artifactSha256, String semanticStatus) throws Exception {
        var rowClass = Class.forName("com.company.skillplatform.codegraph.application.CodeGraphQueryService$BindingRow");
        Constructor<?> ctor = rowClass.getDeclaredConstructor(long.class, int.class, String.class, long.class,
                String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(bindingId, version, "GITNEXUS", bundleId, artifactKey, artifactSha256, semanticStatus);
    }
}

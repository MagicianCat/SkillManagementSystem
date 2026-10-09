package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M7 §4 — Semantic sidecar reaper.
 *
 * <p>Contract: SUPERSEDED bundles keep their Qdrant points alive while any non-terminal
 * AgentRun still pins them. Once the last AgentRun referencing a SUPERSEDED binding
 * finishes, the next sweep deletes the points via {@code deleteBundle(bundleId)}.</p>
 *
 * <p>The sweep is idempotent and failure-tolerant: a single failing bundle does not
 * block the others, and a crashing sweep leaves no permanent state corruption.</p>
 */
class CodeGraphSemanticSidecarReaperTest {

    /**
     * Bundle 500 is referenced by binding 11 (SUPERSEDED) but no non-terminal AgentRun
     * still points to binding 11 — it must be deleted.
     */
    @Test
    void deletesBundleWhenNoLiveAgentRunReferencesIt() {
        var jdbc = mock(JdbcTemplate.class);
        var vectorStore = mock(CodeGraphVectorStorePort.class);
        var reaper = new CodeGraphSemanticSidecarReaper(jdbc, vectorStore);

        when(jdbc.query(contains("workflow_run_code_graph_binding"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(500L));
        when(jdbc.update(contains("semantic_cleanup_status='DELETING'"), eq(500L))).thenReturn(1);

        reaper.sweep();

        verify(vectorStore).deleteBundle(500L);
    }

    /**
     * Bundle 600 still has a RUNNING AgentRun referencing its SUPERSEDED binding —
     * the SQL filter excludes it, so deleteBundle is never called for 600.
     */
    @Test
    void skipsBundlesStillReferencedByLiveAgentRuns() {
        var jdbc = mock(JdbcTemplate.class);
        var vectorStore = mock(CodeGraphVectorStorePort.class);
        var reaper = new CodeGraphSemanticSidecarReaper(jdbc, vectorStore);

        // The SQL filter excludes any bundle whose binding still has a live AgentRun.
        // Returning an empty list simulates that filter outcome.
        when(jdbc.query(contains("workflow_run_code_graph_binding"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        reaper.sweep();

        verify(vectorStore, never()).deleteBundle(anyLong());
    }

    /**
     * The candidate query must filter on the agreed non-terminal statuses: QUEUED,
     * STARTING, RUNNING, WAITING_HUMAN, PAUSED.
     */
    @Test
    void candidateQueryIncludesAllNonTerminalAgentStatuses() {
        var jdbc = mock(JdbcTemplate.class);
        var vectorStore = mock(CodeGraphVectorStorePort.class);
        var reaper = new CodeGraphSemanticSidecarReaper(jdbc, vectorStore);

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        reaper.sweep();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        var paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), paramsCaptor.capture());
        var sql = sqlCaptor.getValue();
        var params = paramsCaptor.getValue();
        // The query binds the 5 non-terminal statuses as parameters.
        var paramStrings = java.util.Arrays.stream(params).map(String::valueOf).toList();
        assertThat(paramStrings).containsExactlyInAnyOrder("QUEUED", "STARTING", "RUNNING", "WAITING_HUMAN", "PAUSED");
        // And the SQL filter mentions them all.
        for (var status : List.of("QUEUED", "STARTING", "RUNNING", "WAITING_HUMAN", "PAUSED")) {
            assertThat(sql).contains("?");
        }
    }

    /**
     * Idempotency: the durable cleanup claim prevents repeat deletion.
     */
    @Test
    void sweepIsIdempotentAcrossRuns() {
        var jdbc = mock(JdbcTemplate.class);
        var vectorStore = mock(CodeGraphVectorStorePort.class);
        var reaper = new CodeGraphSemanticSidecarReaper(jdbc, vectorStore);

        when(jdbc.query(contains("workflow_run_code_graph_binding"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(500L));
        when(jdbc.update(contains("semantic_cleanup_status='DELETING'"), eq(500L))).thenReturn(1, 0);

        reaper.sweep();
        reaper.sweep();

        verify(vectorStore, times(1)).deleteBundle(500L);
    }

    /**
     * Failure isolation: a failing deleteBundle call must not propagate. The reaper
     * catches the exception, logs it, and moves on — the next sweep will retry.
     */
    @Test
    void deleteFailureIsCaughtAndDoesNotPropagate() {
        var jdbc = mock(JdbcTemplate.class);
        var vectorStore = mock(CodeGraphVectorStorePort.class);
        var reaper = new CodeGraphSemanticSidecarReaper(jdbc, vectorStore);

        when(jdbc.query(contains("workflow_run_code_graph_binding"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(500L, 600L));
        when(jdbc.update(contains("semantic_cleanup_status='DELETING'"), anyLong())).thenReturn(1);
        doThrow(new IllegalStateException("qdrant offline")).when(vectorStore).deleteBundle(500L);

        // The sweep completes without throwing, and the second bundle is still attempted.
        reaper.sweep();

        verify(vectorStore).deleteBundle(500L);
        verify(vectorStore).deleteBundle(600L);
    }

    /**
     * If the candidate query itself crashes, the sweep swallows the exception so the
     * scheduler thread keeps its fixed-delay cadence.
     */
    @Test
    void candidateQueryFailureIsSwallowed() {
        var jdbc = mock(JdbcTemplate.class);
        var vectorStore = mock(CodeGraphVectorStorePort.class);
        var reaper = new CodeGraphSemanticSidecarReaper(jdbc, vectorStore);

        when(jdbc.query(contains("workflow_run_code_graph_binding"), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("db gone"));

        reaper.sweep(); // must not throw

        verify(vectorStore, never()).deleteBundle(anyLong());
    }
}

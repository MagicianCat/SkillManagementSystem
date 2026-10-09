package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkflowProperties;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.git.application.GitWorkflowRepositoryFreezeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M7 unit tests for {@link WorkflowCodeGraphAppendService}. The service is responsible
 * for the "append repositories to a RUNNING workflow" concurrency contract:
 *
 * <ul>
 *   <li>Case 6 — v1=A+B ACTIVE; append C. Workflow + v1 remain usable while v2 builds;
 *       exactly one v(N+1) binding is created.</li>
 *   <li>Merge — consecutive appends during a BUILDING job collapse into one PENDING
 *       update request; only one REPO_APPEND job runs at a time.</li>
 *   <li>Freeze safety — only newly appended rows are re-frozen; A and B's frozen
 *       commits are never advanced.</li>
 *   <li>Failure isolation — a failing v2 marks only the new PREPARING binding FAILED;
 *       v1 stays ACTIVE and the workflow status is untouched.</li>
 * </ul>
 *
 * The JDBC layer is mocked; the focus is on orchestration decisions, not SQL syntax.
 */
class WorkflowCodeGraphAppendServiceTest {

    @Test
    void rejectsAppendWhenWorkflowIsNotRunning() {
        var jdbc = mock(JdbcTemplate.class);
        stubWorkflowStatus(jdbc, "PREPARING_CODE_GRAPH", 0);
        var service = service(jdbc, mock(GitWorkflowRepositoryFreezeService.class),
                mock(CodeGraphPreparationService.class), mock(CodeGraphLifecyclePublisher.class));

        assertThatThrownBy(() -> service.onRepositoriesAppended(31L, 7L, List.of(100L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void rejectsAppendWhenNoActiveBinding() {
        var jdbc = mock(JdbcTemplate.class);
        stubWorkflowStatus(jdbc, "RUNNING", 0);
        var service = service(jdbc, mock(GitWorkflowRepositoryFreezeService.class),
                mock(CodeGraphPreparationService.class), mock(CodeGraphLifecyclePublisher.class));

        assertThatThrownBy(() -> service.onRepositoriesAppended(31L, 7L, List.of(100L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACTIVE");
    }

    /**
     * Case 6 freeze guarantee: appending C must never advance the frozen commit of
     * already-frozen rows A and B. The implementation filters out rows that already
     * carry a resolved_commit_sha.
     */
    @Test
    void freezesOnlyNewlyAppendedRowsAndNeverAdvancesExisting() {
        var jdbc = mock(JdbcTemplate.class);
        stubWorkflowStatus(jdbc, "RUNNING", 1);
        stubLockRun(jdbc);
        stubOpenAppendJob(jdbc, null);
        // Rows: A and B already frozen; only C (id=3) needs freezing.
        stubWorkflowRepositoryRows(jdbc, rows(
                new Object[]{1L, "https://a", "main", "shaA"},
                new Object[]{2L, "https://b", "main", "shaB"},
                new Object[]{3L, "https://c", "main", null}
        ));
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        when(frozen.inputs(31L)).thenReturn(List.of(
                repo("a", "backend"), repo("b", "frontend"), repo("c", "infra")
        ));
        stubPendingUpdateInsert(jdbc, 88L);
        var preparation = mock(CodeGraphPreparationService.class);
        when(preparation.prepare(eq(7L), any(GenerationCommand.class)))
                .thenReturn(new CodeGraphGenerationCoordinator.StartResult(99L, "engine-99", "BUILDING", false, "hash"));
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        service.onRepositoriesAppended(31L, 7L, List.of(3L));

        // Only id=3 was frozen — A and B were skipped because their resolved_commit_sha was non-null.
        verify(frozen, times(1)).freezeOne(eq(3L), eq("https://c"), eq("main"));
        verify(frozen, never()).freezeOne(eq(1L), anyString(), anyString());
        verify(frozen, never()).freezeOne(eq(2L), anyString(), anyString());
    }

    /**
     * Merge behaviour: while a REPO_APPEND job is BUILDING, a second append lands as a
     * PENDING update request — no new job is started.
     */
    @Test
    void queuesPendingUpdateWhenAppendJobIsInFlight() {
        var jdbc = mock(JdbcTemplate.class);
        stubWorkflowStatus(jdbc, "RUNNING", 1);
        stubLockRun(jdbc);
        stubOpenAppendJob(jdbc, 42L);
        stubWorkflowRepositoryRows(jdbc, rows(new Object[]{3L, "https://c", "main", null}));
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        when(frozen.inputs(31L)).thenReturn(List.of(repo("a", "backend"), repo("c", "infra")));
        stubPendingUpdateInsert(jdbc, 88L);
        var preparation = mock(CodeGraphPreparationService.class);
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        var outcome = service.onRepositoriesAppended(31L, 7L, List.of(3L));

        assertThat(outcome.kind()).isEqualTo(WorkflowCodeGraphAppendService.AppendOutcome.Kind.QUEUED);
        assertThat(outcome.jobId()).isEqualTo(42L);
        verify(preparation, never()).prepare(anyLong(), any());
        verify(lifecycle).event(eq(31L), eq("code_graph.update.queued"), argThat(map ->
                Long.valueOf(88L).equals(map.get("updateRequestId")) &&
                        Long.valueOf(42L).equals(map.get("inFlightJobId"))));
    }

    /**
     * Happy path: no in-flight REPO_APPEND job — the append triggers an immediate
     * REPO_APPEND generation with reason='REPO_APPEND'.
     */
    @Test
    void startsImmediateAppendJobWhenNoJobInFlight() {
        var jdbc = mock(JdbcTemplate.class);
        stubWorkflowStatus(jdbc, "RUNNING", 1);
        stubLockRun(jdbc);
        stubOpenAppendJob(jdbc, null);
        stubWorkflowRepositoryRows(jdbc, rows(new Object[]{3L, "https://c", "main", null}));
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        when(frozen.inputs(31L)).thenReturn(List.of(repo("a", "backend"), repo("c", "infra")));
        stubPendingUpdateInsert(jdbc, 88L);
        var preparation = mock(CodeGraphPreparationService.class);
        var command = new java.util.concurrent.atomic.AtomicReference<GenerationCommand>();
        when(preparation.prepare(eq(7L), any())).thenAnswer(invocation -> {
            command.set(invocation.getArgument(1));
            return new CodeGraphGenerationCoordinator.StartResult(99L, "engine-99", "BUILDING", false, "hash");
        });
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        var outcome = service.onRepositoriesAppended(31L, 7L, List.of(3L));

        assertThat(outcome.kind()).isEqualTo(WorkflowCodeGraphAppendService.AppendOutcome.Kind.STARTED);
        assertThat(outcome.jobId()).isEqualTo(99L);
        assertThat(command.get().reason()).isEqualTo("REPO_APPEND");
        assertThat(command.get().repositories()).hasSize(2);
    }

    /**
     * Failure isolation: if the REPO_APPEND submit throws, the update request is marked
     * FAILED and the {@code code_graph.update.failed} event is emitted — but neither
     * {@code workflow_run.status} nor the ACTIVE binding row is touched by this service.
     */
    @Test
    void marksUpdateFailedWithoutTouchingWorkflowStatusOnSubmitFailure() {
        var jdbc = mock(JdbcTemplate.class);
        stubWorkflowStatus(jdbc, "RUNNING", 1);
        stubLockRun(jdbc);
        stubOpenAppendJob(jdbc, null);
        stubWorkflowRepositoryRows(jdbc, rows(new Object[]{3L, "https://c", "main", null}));
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        when(frozen.inputs(31L)).thenReturn(List.of(repo("a", "backend"), repo("c", "infra")));
        stubPendingUpdateInsert(jdbc, 88L);
        var preparation = mock(CodeGraphPreparationService.class);
        when(preparation.prepare(eq(7L), any())).thenThrow(new IllegalStateException("worker offline"));
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        assertThatThrownBy(() -> service.onRepositoriesAppended(31L, 7L, List.of(3L)))
                .isInstanceOf(IllegalStateException.class);

        // update.failed event was emitted.
        verify(lifecycle).event(eq(31L), eq("code_graph.update.failed"), any());
        // Verify the update request was marked FAILED.
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object.class), any(Object.class), any(Object.class));
        var failedUpdate = sql.getAllValues().stream()
                .filter(s -> s.contains("code_graph_update_request") && s.contains("'FAILED'"))
                .findFirst();
        assertThat(failedUpdate).isPresent();
        // No UPDATE against workflow_run (that would belong to the INITIAL path).
        assertThat(sql.getAllValues().stream().noneMatch(s ->
                s.startsWith("UPDATE workflow_run ") && s.contains("CODE_GRAPH_PREPARATION_FAILED"))).isTrue();
    }

    // ---------- stubs -------------------------------------------------------

    private static void stubWorkflowStatus(JdbcTemplate jdbc, String status, int activeBindings) {
        var row = new Object[]{status, activeBindings};
        when(jdbc.query(
                eq("SELECT w.status,(SELECT COUNT(*) FROM workflow_run_code_graph_binding b WHERE b.workflow_run_id=w.id AND b.status='ACTIVE') FROM workflow_run w WHERE w.id=?"),
                org.mockito.ArgumentMatchers.<RowMapper<Object[]>>any(), eq(31L)))
                .thenReturn(java.util.Collections.singletonList(row));
    }

    private static void stubLockRun(JdbcTemplate jdbc) {
        when(jdbc.queryForObject(eq("SELECT id FROM workflow_run WHERE id=? FOR UPDATE"), eq(Long.class), eq(31L)))
                .thenReturn(31L);
    }

    private static void stubOpenAppendJob(JdbcTemplate jdbc, Long jobId) {
        when(jdbc.query(
                contains("FROM code_graph_generation_job WHERE workflow_run_id=? AND job_type='REPO_APPEND' AND status IN"),
                any(RowMapper.class), eq(31L)))
                .thenReturn(jobId == null ? List.of() : List.of(jobId));
    }

    private static void stubWorkflowRepositoryRows(JdbcTemplate jdbc, List<Object[]> rows) {
        when(jdbc.query(
                startsWith("SELECT id,normalized_url,tracked_branch,resolved_commit_sha FROM workflow_run_git_repository"),
                org.mockito.ArgumentMatchers.<RowMapper<Object[]>>any(), any(Object[].class)))
                .thenAnswer(invocation -> rows);
    }

    private static List<Object[]> rows(Object[]... rows) {
        return java.util.Arrays.asList(rows);
    }

    private static void stubPendingUpdateInsert(JdbcTemplate jdbc, long newId) {
        when(jdbc.queryForObject(
                eq("SELECT id FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id DESC LIMIT 1"),
                eq(Long.class), eq(31L)))
                .thenReturn(newId);
    }

    // ---------- helpers -----------------------------------------------------

    private static WorkflowCodeGraphAppendService service(JdbcTemplate jdbc,
                                                          GitWorkflowRepositoryFreezeService frozen,
                                                          CodeGraphPreparationService preparation,
                                                          CodeGraphLifecyclePublisher lifecycle) {
        var properties = new CodeGraphWorkflowProperties("GITNEXUS", "1", "adapter", "cfg", "group");
        // freezeOne is exercised in freeze-safety tests; default mock returns null and
        // would trip the "freeze failed" guard. Return a valid FrozenRepository.
        lenient().when(frozen.freezeOne(anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> new com.company.skillplatform.git.domain.GitRemotePort.FrozenRepository(
                        invocation.getArgument(1), invocation.getArgument(1), invocation.getArgument(2),
                        "c".repeat(40), "d".repeat(40), invocation.getArgument(1),
                        "file:///src.tar", "e".repeat(64)));
        return new WorkflowCodeGraphAppendService(jdbc, new ObjectMapper(), frozen, preparation, lifecycle, properties);
    }

    private static RepositoryInput repo(String key, String logical) {
        return new RepositoryInput(key, logical, "a".repeat(40), "b".repeat(40),
                "file:///source.tar", "c".repeat(64));
    }
}

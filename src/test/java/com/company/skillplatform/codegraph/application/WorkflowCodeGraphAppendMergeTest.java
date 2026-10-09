package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkflowProperties;
import com.company.skillplatform.git.application.GitWorkflowRepositoryFreezeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M7 — consecutive appends merge into a single next build.
 *
 * <p>Scenario: workflow is RUNNING with v1=A+B ACTIVE. User appends C (v2 build starts
 * for A+B+C). While v2 is still BUILDING, the user appends D. The system must:</p>
 *
 * <ol>
 *   <li>NOT cancel the in-flight v2 build.</li>
 *   <li>Record the D append as a PENDING {@code code_graph_update_request}.</li>
 *   <li>When v2 finishes, drain the pending request and build v3 = A+B+C+D (the
 *       full frozen set at that moment).</li>
 *   <li>Never run two REPO_APPEND jobs in parallel for the same workflow run.</li>
 * </ol>
 */
class WorkflowCodeGraphAppendMergeTest {

    /**
     * First append: no in-flight job, so we start v2 = A+B+C immediately. Second
     * append arrives while v2 is BUILDING: it lands as a PENDING update request.
     * No second job is started.
     */
    @Test
    void consecutiveAppendsDoNotStartParallelJobs() {
        var jdbc = mock(JdbcTemplate.class);
        stubRunActive(jdbc);
        stubLock(jdbc);
        // First call: no open job. Second call: job 42 is open.
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        when(frozen.inputs(31L)).thenReturn(List.of(
                repo("a", "backend"), repo("b", "frontend"), repo("c", "infra")));
        stubRepositoryRows(jdbc);
        stubPendingInsert(jdbc, 88L);

        var preparation = mock(CodeGraphPreparationService.class);
        when(preparation.prepare(eq(7L), any(GenerationCommand.class)))
                .thenReturn(new CodeGraphGenerationCoordinator.StartResult(42L, "engine-42", "BUILDING", false, "h1"));
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        // First append: starts v2 = A+B+C.
        stubOpenAppendJob(jdbc, null);
        var first = service.onRepositoriesAppended(31L, 7L, List.of(3L));
        assertThat(first.kind()).isEqualTo(WorkflowCodeGraphAppendService.AppendOutcome.Kind.STARTED);
        verify(preparation, times(1)).prepare(eq(7L), any());

        // Second append: v2 (job 42) is BUILDING — must queue, not start a parallel job.
        stubOpenAppendJob(jdbc, 42L);
        when(frozen.inputs(31L)).thenReturn(List.of(
                repo("a", "backend"), repo("b", "frontend"), repo("c", "infra"), repo("d", "shared")));
        stubPendingInsert(jdbc, 89L);
        var second = service.onRepositoriesAppended(31L, 7L, List.of(4L));

        assertThat(second.kind()).isEqualTo(WorkflowCodeGraphAppendService.AppendOutcome.Kind.QUEUED);
        assertThat(second.jobId()).isEqualTo(42L);
        // prepare() was NOT called again — no parallel job.
        verify(preparation, times(1)).prepare(eq(7L), any());
    }

    /**
     * After v2 finishes (READY), the drain pass picks up the PENDING update for D and
     * starts v3 = A+B+C+D (computed fresh from the frozen repository set at drain time).
     */
    @Test
    void drainStartsNextJobWithFullRepositorySet() {
        var jdbc = mock(JdbcTemplate.class);
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        var preparation = mock(CodeGraphPreparationService.class);
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        // A PENDING update request exists for D.
        when(jdbc.query(
                eq("SELECT id,triggered_by FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id LIMIT 1"),
                any(RowMapper.class), eq(31L)))
                .thenReturn(List.of(new long[]{89L, 7L}));
        stubLock(jdbc);
        stubOpenAppendJob(jdbc, null); // v2 finished
        // Frozen set at drain time contains all four repositories.
        when(frozen.inputs(31L)).thenReturn(List.of(
                repo("a", "backend"), repo("b", "frontend"), repo("c", "infra"), repo("d", "shared")));
        var capturedCommand = new java.util.concurrent.atomic.AtomicReference<GenerationCommand>();
        when(preparation.prepare(eq(7L), any(GenerationCommand.class))).thenAnswer(invocation -> {
            capturedCommand.set(invocation.getArgument(1));
            return new CodeGraphGenerationCoordinator.StartResult(50L, "engine-50", "BUILDING", false, "h2");
        });

        service.drainPendingUpdates(31L);

        // prepare was invoked with the FULL frozen set (all four repos), not just D.
        var command = capturedCommand.get();
        assertThat(command).isNotNull();
        assertThat(command.reason()).isEqualTo("REPO_APPEND");
        assertThat(command.repositories()).hasSize(4);
        assertThat(command.repositories().stream().map(RepositoryInput::repositoryKey).sorted().toList())
                .containsExactly("a", "b", "c", "d");
        // The PENDING update was marked BUILDING with the new job id.
        verify(jdbc).update(contains("code_graph_update_request SET status='BUILDING'"), eq(50L), eq(89L));
    }

    /**
     * If two drains race (e.g. the poller fired twice for the same job), the second
     * one sees the open job and exits without starting a duplicate build.
     */
    @Test
    void concurrentDrainsSerializeOnWorkflowRunLock() {
        var jdbc = mock(JdbcTemplate.class);
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        var preparation = mock(CodeGraphPreparationService.class);
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        when(jdbc.query(
                eq("SELECT id,triggered_by FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id LIMIT 1"),
                any(RowMapper.class), eq(31L)))
                .thenReturn(List.of(new long[]{89L, 7L}));
        stubLock(jdbc);
        // Simulate a race: by the time drain acquires the workflow lock, another job is open.
        stubOpenAppendJob(jdbc, 42L);

        service.drainPendingUpdates(31L);

        // No new job was started; the open one wins.
        verify(preparation, never()).prepare(anyLong(), any());
        // And the PENDING row was NOT touched (it stays PENDING for the next drain).
        verify(jdbc, never()).update(contains("code_graph_update_request SET status='BUILDING'"), any(), any());
    }

    /**
     * If a drain fails to submit the next build, the update is marked FAILED with an
     * error code, and the {@code code_graph.update.failed} event is emitted. The
     * workflow status is never touched.
     */
    @Test
    void drainFailureMarksUpdateFailedWithoutTouchingWorkflow() {
        var jdbc = mock(JdbcTemplate.class);
        var frozen = mock(GitWorkflowRepositoryFreezeService.class);
        var preparation = mock(CodeGraphPreparationService.class);
        var lifecycle = mock(CodeGraphLifecyclePublisher.class);
        var service = service(jdbc, frozen, preparation, lifecycle);

        when(jdbc.query(
                eq("SELECT id,triggered_by FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id LIMIT 1"),
                any(RowMapper.class), eq(31L)))
                .thenReturn(List.of(new long[]{89L, 7L}));
        stubLock(jdbc);
        stubOpenAppendJob(jdbc, null);
        when(frozen.inputs(31L)).thenReturn(List.of(repo("a", "backend"), repo("d", "shared")));
        when(preparation.prepare(eq(7L), any())).thenThrow(new IllegalStateException("worker offline"));

        service.drainPendingUpdates(31L);

        // update.failed event was emitted.
        verify(lifecycle).event(eq(31L), eq("code_graph.update.failed"), any());
        // No workflow_run UPDATE was issued from this code path.
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(captor.capture(), any(Object.class), any(Object.class), any(Object.class));
        assertThat(captor.getAllValues().stream().noneMatch(sql ->
                sql.startsWith("UPDATE workflow_run ") && sql.contains("CODE_GRAPH_PREPARATION_FAILED"))).isTrue();
    }

    // ---------- stubs -------------------------------------------------------

    private void stubRunActive(JdbcTemplate jdbc) {
        var row = new Object[]{"RUNNING", 1};
        when(jdbc.query(
                eq("SELECT w.status,(SELECT COUNT(*) FROM workflow_run_code_graph_binding b WHERE b.workflow_run_id=w.id AND b.status='ACTIVE') FROM workflow_run w WHERE w.id=?"),
                org.mockito.ArgumentMatchers.<RowMapper<Object[]>>any(), eq(31L)))
                .thenReturn(java.util.Collections.singletonList(row));
    }

    private void stubLock(JdbcTemplate jdbc) {
        when(jdbc.queryForObject(eq("SELECT id FROM workflow_run WHERE id=? FOR UPDATE"), eq(Long.class), eq(31L)))
                .thenReturn(31L);
    }

    private void stubOpenAppendJob(JdbcTemplate jdbc, Long jobId) {
        when(jdbc.query(
                contains("FROM code_graph_generation_job WHERE workflow_run_id=? AND job_type='REPO_APPEND' AND status IN"),
                any(RowMapper.class), eq(31L)))
                .thenReturn(jobId == null ? List.of() : List.of(jobId));
    }

    private void stubRepositoryRows(JdbcTemplate jdbc) {
        var row = new Object[]{3L, "https://c", "main", null};
        when(jdbc.query(
                startsWith("SELECT id,normalized_url,tracked_branch,resolved_commit_sha FROM workflow_run_git_repository"),
                org.mockito.ArgumentMatchers.<RowMapper<Object[]>>any(), any(Object[].class)))
                .thenAnswer(inv -> java.util.Collections.singletonList(row));
    }

    private void stubPendingInsert(JdbcTemplate jdbc, long newId) {
        when(jdbc.queryForObject(
                eq("SELECT id FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id DESC LIMIT 1"),
                eq(Long.class), eq(31L)))
                .thenReturn(newId);
    }

    private static WorkflowCodeGraphAppendService service(JdbcTemplate jdbc,
                                                          GitWorkflowRepositoryFreezeService frozen,
                                                          CodeGraphPreparationService preparation,
                                                          CodeGraphLifecyclePublisher lifecycle) {
        var properties = new CodeGraphWorkflowProperties("GITNEXUS", "1", "adapter", "cfg", "group");
        // freezeOne default for the merge test: not exercised in drain path, but happy
        // path needs a non-null return.
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

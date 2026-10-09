package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.application.CodeGraphLifecyclePublisher;
import com.company.skillplatform.codegraph.application.CodeGraphReusePlanner;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.Artifact;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort.BuildStatus;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M7 — binding lifecycle and failure isolation in {@link JdbcCodeGraphMetadataStore}.
 *
 * <p>The metadata store is the single place that knows the difference between the
 * INITIAL preparation path (which must flip {@code workflow_run.status}) and the
 * REPO_APPEND update path (which must not). These tests pin the contract:</p>
 *
 * <ul>
 *   <li>Case 6 — REPO_APPEND success: binding is activated, job is READY, but
 *       {@code workflow_run.status} is never touched (no READY_TO_START write).</li>
 *   <li>Failure isolation — REPO_APPEND failure: binding/bundle/job are marked FAILED,
 *       but {@code workflow_run.status} is not moved to CODE_GRAPH_PREPARATION_FAILED
 *       (the existing ACTIVE binding keeps serving the running workflow).</li>
 *   <li>Regression — INITIAL failure still flips the workflow to CODE_GRAPH_PREPARATION_FAILED.</li>
 *   <li>Event — REPO_APPEND success emits {@code code_graph.binding.activated} with
 *       old/new binding ids, versions, bundle id, and repository count.</li>
 * </ul>
 */
class JdbcCodeGraphMetadataStoreM7Test {

    private JdbcTemplate jdbc;
    private CodeGraphLifecyclePublisher lifecycle;
    private JdbcCodeGraphMetadataStore store;
    private List<String> updates;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        lifecycle = mock(CodeGraphLifecyclePublisher.class);
        store = new JdbcCodeGraphMetadataStore(jdbc, new ObjectMapper(), lifecycle);
        updates = new ArrayList<>();
        // JdbcTemplate.update(String, Object...) is varargs; Mockito sees each call as
        // (String, Object, Object, ...). Stub a generic answer that captures the SQL.
        when(jdbc.update(anyString(), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class))).thenAnswer(inv -> { updates.add(inv.getArgument(0)); return 1; });
    }

    /**
     * Case 6 happy path: a REPO_APPEND job completes successfully. The new binding is
     * activated, the job is READY — but the workflow status is NOT flipped (the run
     * was already RUNNING before the append and stays RUNNING).
     */
    @Test
    void repoAppendSuccessActivatesBindingWithoutTouchingWorkflowStatus() {
        stubJobForComplete(50L, 200L, "REPO_APPEND", "hash-v2", 600L, "READY");
        var artifact = new Artifact("file:///v2.tar", "a".repeat(64), "GITNEXUS", "1", "adapter", "FULL");
        var status = new BuildStatus("engine-1", State.SUCCEEDED, 100, "PUBLISH", artifact, null, null);

        var completed = store.complete(50L, status);

        assertThat(completed).isTrue();
        // Binding was activated.
        assertThat(updates.stream().anyMatch(sql -> sql.contains("workflow_run_code_graph_binding SET status='ACTIVE'"))).isTrue();
        // Old binding was superseded.
        assertThat(updates.stream().anyMatch(sql -> sql.contains("status='SUPERSEDED'") && sql.contains("id<>"))).isTrue();
        // Job became READY.
        assertThat(updates.stream().anyMatch(sql -> sql.contains("code_graph_generation_job SET status='READY'"))).isTrue();
        // BUT: workflow_run.status was NEVER touched. READY_TO_START only happens for INITIAL.
        assertThat(updates.stream().noneMatch(sql -> sql.startsWith("UPDATE workflow_run SET status='READY_TO_START'"))).isTrue();
    }

    /**
     * REPO_APPEND failure: the new PREPARING binding, the BUILDING bundle and the job
     * are marked FAILED — but the workflow status stays RUNNING (it is never rewritten
     * to CODE_GRAPH_PREPARATION_FAILED).
     */
    @Test
    void repoAppendFailureMarksRowsFailedButLeavesWorkflowRunning() {
        stubJobForFail(60L, 210L, "REPO_APPEND");

        store.fail(60L, "WORKER_CRASHED", "worker went away");

        // Job marked FAILED.
        assertThat(updates.stream().anyMatch(sql -> sql.contains("code_graph_generation_job SET status='FAILED'"))).isTrue();
        // New binding marked FAILED.
        assertThat(updates.stream().anyMatch(sql -> sql.contains("workflow_run_code_graph_binding SET status='FAILED'") && sql.contains("status='PREPARING'"))).isTrue();
        // Bundle marked FAILED.
        assertThat(updates.stream().anyMatch(sql -> sql.contains("code_graph_bundle") && sql.contains("status='FAILED'"))).isTrue();
        // Workflow_run.status NOT flipped to CODE_GRAPH_PREPARATION_FAILED.
        assertThat(updates.stream().noneMatch(sql -> sql.contains("workflow_run") && sql.contains("CODE_GRAPH_PREPARATION_FAILED"))).isTrue();
    }

    /**
     * Regression: INITIAL job failure still flips the workflow to
     * CODE_GRAPH_PREPARATION_FAILED. This was the M6 contract and must not regress.
     */
    @Test
    void initialFailureStillFlipsWorkflowToPreparationFailed() {
        stubJobForFail(70L, 220L, "INITIAL");

        store.fail(70L, "ARTIFACT_MISSING", "worker produced no artifact");

        // workflow_run status IS flipped to CODE_GRAPH_PREPARATION_FAILED.
        assertThat(updates.stream().anyMatch(sql ->
                sql.contains("workflow_run") && sql.contains("CODE_GRAPH_PREPARATION_FAILED"))).isTrue();
    }

    /**
     * REPO_APPEND success emits {@code code_graph.binding.activated} after the swap,
     * carrying the old binding (the one that was just superseded), the new binding,
     * both version numbers, the new bundle id, and the repository count.
     */
    @Test
    void repoAppendSuccessPublishesBindingActivatedEvent() {
        stubJobForComplete(50L, 200L, "REPO_APPEND", "hash-v2", 600L, "READY");
        // Previous (now superseded) binding row.
        when(jdbc.query(argThat(sql -> sql != null && sql.contains("status='SUPERSEDED'") && sql.contains("ORDER BY time_updated DESC")),
                any(RowMapper.class), eq(31L)))
                .thenAnswer(inv -> List.of(Map.of("bindingId", 199L, "version", 1, "bundleId", 500L)));
        // Current binding version + repository_set_hash.
        when(jdbc.queryForObject(
                eq("SELECT version_no,repository_set_hash FROM workflow_run_code_graph_binding WHERE id=?"),
                any(RowMapper.class), eq(200L)))
                .thenReturn(Map.of("version", 2, "repositorySetHash", "hash-v2"));
        // Repository count for the new bundle.
        when(jdbc.queryForObject(
                eq("SELECT COUNT(*) FROM code_graph_bundle_repository WHERE bundle_id=?"),
                eq(Integer.class), eq(600L)))
                .thenReturn(3);
        var artifact = new Artifact("file:///v2.tar", "a".repeat(64), "GITNEXUS", "1", "adapter", "FULL");
        var status = new BuildStatus("engine-1", State.SUCCEEDED, 100, "PUBLISH", artifact, null, null);

        store.complete(50L, status);

        var captor = ArgumentCaptor.forClass(Map.class);
        verify(lifecycle).event(eq(31L), eq("code_graph.binding.activated"), captor.capture());
        var payload = captor.getValue();
        assertThat(payload).containsEntry("bindingId", 200L);
        assertThat(payload).containsEntry("bundleId", 600L);
        assertThat(payload).containsEntry("version", 2);
        assertThat(payload).containsEntry("repositoryCount", 3);
        assertThat(payload).containsEntry("previousBindingId", 199L);
        assertThat(payload).containsEntry("previousVersion", 1);
        assertThat(payload).containsEntry("previousBundleId", 500L);
    }

    /**
     * The same event is NOT emitted for the INITIAL path — initial preparation already
     * has its own {@code code_graph.bundle.ready} semantics.
     */
    @Test
    void initialSuccessDoesNotPublishBindingActivatedEvent() {
        stubJobForComplete(51L, 201L, "INITIAL", "hash-v1", 500L, "READY");
        var artifact = new Artifact("file:///v1.tar", "a".repeat(64), "GITNEXUS", "1", "adapter", "FULL");
        var status = new BuildStatus("engine-1", State.SUCCEEDED, 100, "PUBLISH", artifact, null, null);

        store.complete(51L, status);

        verify(lifecycle, never()).event(anyLong(), eq("code_graph.binding.activated"), any());
    }

    // ---------- stubs -------------------------------------------------------

    /**
     * Stubs the JDBC reads that happen inside {@code complete()}:
     * <ol>
     *   <li>SELECT target_binding_id, build_request_json FROM code_graph_generation_job ... FOR UPDATE</li>
     *   <li>SELECT bundle_id FROM workflow_run_code_graph_binding WHERE id=?</li>
     *   <li>SELECT status FROM code_graph_bundle WHERE id=? FOR UPDATE</li>
     *   <li>SELECT job_type FROM code_graph_generation_job WHERE id=?</li>
     * </ol>
     */
    private void stubJobForComplete(long jobId, long bindingId, String jobType, String bundleHash, long bundleId, String bundleStatus) {
        var storedBuildJson = storedBuildJson(bundleHash);
        when(jdbc.query(
                eq("SELECT workflow_run_id FROM code_graph_generation_job WHERE id=? AND status='BUILDING'"),
                any(RowMapper.class), eq(jobId)))
                .thenReturn(List.of(31L));
        when(jdbc.queryForObject(eq("SELECT id FROM workflow_run WHERE id=? FOR UPDATE"), eq(Long.class), eq(31L)))
                .thenReturn(31L);
        when(jdbc.query(
                eq("SELECT target_binding_id,build_request_json FROM code_graph_generation_job WHERE id=? AND status='BUILDING' FOR UPDATE"),
                any(RowMapper.class), eq(jobId)))
                .thenAnswer(inv -> List.of(newStoredJob(bindingId, storedBuildJson)));
        when(jdbc.queryForObject(
                eq("SELECT bundle_id FROM workflow_run_code_graph_binding WHERE id=?"),
                eq(Long.class), eq(bindingId)))
                .thenReturn(bundleId);
        when(jdbc.queryForObject(
                eq("SELECT status FROM code_graph_bundle WHERE id=? FOR UPDATE"),
                eq(String.class), eq(bundleId)))
                .thenReturn(bundleStatus);
        when(jdbc.query(
                eq("SELECT job_type FROM code_graph_generation_job WHERE id=?"),
                any(RowMapper.class), eq(jobId)))
                .thenAnswer(inv -> List.of(jobType));
        // Snapshot lookup for the reuse merge loop: return the same id so the insert works.
        when(jdbc.queryForObject(
                eq("SELECT id FROM code_graph_repository_snapshot WHERE graph_fingerprint=? AND status='READY'"),
                eq(Long.class), anyString()))
                .thenReturn(999L);
    }

    private void stubJobForFail(long jobId, long bindingId, String jobType) {
        when(jdbc.query(
                eq("SELECT workflow_run_id,target_binding_id FROM code_graph_generation_job WHERE id=?"),
                any(RowMapper.class), eq(jobId)))
                .thenAnswer(inv -> List.of(new long[]{31L, bindingId}));
        when(jdbc.queryForObject(eq("SELECT id FROM workflow_run WHERE id=? FOR UPDATE"), eq(Long.class), eq(31L)))
                .thenReturn(31L);
        when(jdbc.query(
                eq("SELECT target_binding_id FROM code_graph_generation_job WHERE id=? FOR UPDATE"),
                any(RowMapper.class), eq(jobId)))
                .thenAnswer(inv -> List.of(bindingId));
        when(jdbc.query(
                eq("SELECT job_type FROM code_graph_generation_job WHERE id=?"),
                any(RowMapper.class), eq(jobId)))
                .thenAnswer(inv -> List.of(jobType));
    }

    private String storedBuildJson(String bundleHash) {
        try {
            var mapper = new ObjectMapper();
            var repositories = List.of(Map.<String, Object>of(
                    "repositoryKey", "a", "logicalName", "backend",
                    "commitSha", "a".repeat(40), "treeSha", "b".repeat(40),
                    "sourceArtifactUri", "file:///src.tar", "sourceSha256", "c".repeat(64)));
            var command = Map.<String, Object>of(
                    "workflowRunId", 31, "reason", "INITIAL",
                    "engineType", "GITNEXUS", "engineVersion", "1",
                    "adapterVersion", "adapter", "engineConfigHash", "cfg",
                    "groupConfigHash", "group",
                    "repositories", repositories);
            var plan = Map.<String, Object>of(
                    "repositories", List.<Object>of(),
                    "fullyReusable", true);
            var build = Map.<String, Object>of(
                    "command", command, "plan", plan, "bundleHash", bundleHash);
            return mapper.writeValueAsString(build);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Object newStoredJob(long bindingId, String buildJson) {
        try {
            var cls = Class.forName("com.company.skillplatform.codegraph.infrastructure.JdbcCodeGraphMetadataStore$StoredJob");
            var ctor = cls.getDeclaredConstructor(long.class, Class.forName("com.company.skillplatform.codegraph.infrastructure.JdbcCodeGraphMetadataStore$StoredBuild"));
            ctor.setAccessible(true);
            var buildCls = Class.forName("com.company.skillplatform.codegraph.infrastructure.JdbcCodeGraphMetadataStore$StoredBuild");
            var mapper = new ObjectMapper();
            // Use Jackson to deserialize the build JSON into the actual StoredBuild record.
            // We need to construct via the canonical constructor with named args.
            var buildCtor = buildCls.getDeclaredConstructor(
                    Class.forName("com.company.skillplatform.codegraph.domain.CodeGraphModels$GenerationCommand"),
                    Class.forName("com.company.skillplatform.codegraph.application.CodeGraphReusePlanner$Plan"),
                    String.class);
            buildCtor.setAccessible(true);
            // Read the JSON to extract the fields, then build manually.
            var jsonNode = mapper.readTree(buildJson);
            var cmdNode = jsonNode.get("command");
            var repos = new ArrayList<RepositoryInput>();
            for (var repo : cmdNode.get("repositories")) {
                repos.add(new RepositoryInput(
                        repo.get("repositoryKey").asText(),
                        repo.get("logicalName").asText(),
                        repo.get("commitSha").asText(),
                        repo.get("treeSha").asText(),
                        repo.get("sourceArtifactUri").asText(),
                        repo.get("sourceSha256").asText()));
            }
            var command = new GenerationCommand(
                    cmdNode.get("workflowRunId").asLong(),
                    cmdNode.get("reason").asText(),
                    cmdNode.get("engineType").asText(),
                    cmdNode.get("engineVersion").asText(),
                    cmdNode.get("adapterVersion").asText(),
                    cmdNode.get("engineConfigHash").asText(),
                    cmdNode.get("groupConfigHash").asText(),
                    repos);
            var plan = new CodeGraphReusePlanner.Plan(List.of(), true);
            var build = buildCtor.newInstance(command, plan, jsonNode.get("bundleHash").asText());
            return ctor.newInstance(bindingId, build);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

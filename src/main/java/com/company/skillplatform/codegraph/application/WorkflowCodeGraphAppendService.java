package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkflowProperties;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.git.application.GitWorkflowRepositoryFreezeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * M7: orchestrates "append repositories to a running workflow" without disturbing the
 * in-flight conversation. Concurrency contract:
 *
 * <ul>
 *   <li>At most one BUILDING REPO_APPEND generation job per workflow run.</li>
 *   <li>Appends arriving while a job is BUILDING land as a single PENDING update
 *       request (merging consecutive appends into one next build).</li>
 *   <li>Appends never mutate {@code workflow_run.status}; the running workflow stays
 *       RUNNING and the current ACTIVE binding keeps serving existing Agent runs.</li>
 *   <li>Failure only marks the update request and the new PREPARING binding FAILED;
 *       the previous ACTIVE binding is untouched.</li>
 * </ul>
 */
@Service
public class WorkflowCodeGraphAppendService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowCodeGraphAppendService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final GitWorkflowRepositoryFreezeService frozenRepositories;
    private final CodeGraphPreparationService preparation;
    private final CodeGraphLifecyclePublisher lifecycle;
    private final CodeGraphWorkflowProperties properties;

    public WorkflowCodeGraphAppendService(JdbcTemplate jdbc,
                                          ObjectMapper mapper,
                                          GitWorkflowRepositoryFreezeService frozenRepositories,
                                          CodeGraphPreparationService preparation,
                                          CodeGraphLifecyclePublisher lifecycle,
                                          CodeGraphWorkflowProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.frozenRepositories = frozenRepositories;
        this.preparation = preparation;
        this.lifecycle = lifecycle;
        this.properties = properties;
    }

    /**
     * Entry point invoked after a new repository snapshot has been written to
     * {@code workflow_run_git_repository} for a RUNNING workflow. Decides whether to
     * start an immediate REPO_APPEND build or queue a pending update behind the
     * in-flight one.
     *
     * <p>Must be called from within the same transaction that appended the repository
     * rows so the freeze and the update-request are atomic.</p>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AppendOutcome onRepositoriesAppended(long workflowRunId, long actorId, List<Long> appendedWorkflowRepositoryIds) {
        if (appendedWorkflowRepositoryIds == null || appendedWorkflowRepositoryIds.isEmpty()) {
            throw new IllegalArgumentException("appendedWorkflowRepositoryIds must not be empty");
        }
        requireRunningWithActiveBinding(workflowRunId);

        // Freeze ONLY the newly-appended repositories. Existing rows are left untouched
        // so their resolved_commit_sha is never advanced under the feet of running agents.
        freezeAppended(workflowRunId, appendedWorkflowRepositoryIds);

        // Lock the workflow_run row so two concurrent appends serialise here.
        jdbc.queryForObject("SELECT id FROM workflow_run WHERE id=? FOR UPDATE", Long.class, workflowRunId);

        var openJob = findOpenAppendJob(workflowRunId);
        if (openJob != null) {
            var pendingId = recordPendingUpdate(workflowRunId, actorId, appendedWorkflowRepositoryIds);
            lifecycle.event(workflowRunId, "code_graph.update.queued", Map.of(
                    "updateRequestId", pendingId,
                    "inFlightJobId", openJob.longValue(),
                    "appendedRepositoryCount", appendedWorkflowRepositoryIds.size()));
            return new AppendOutcome(AppendOutcome.Kind.QUEUED, pendingId, openJob.longValue());
        }

        // No open job → start an immediate REPO_APPEND generation.
        var inputs = frozenRepositories.inputs(workflowRunId);
        if (inputs.isEmpty()) {
            throw new BusinessException("CODE_GRAPH_REPOSITORY_NOT_FROZEN",
                    "No frozen repository input is available", HttpStatus.CONFLICT);
        }
        var updateId = recordPendingUpdate(workflowRunId, actorId, appendedWorkflowRepositoryIds);
        try {
            var result = startAppendJob(workflowRunId, actorId, updateId, inputs);
            jdbc.update("UPDATE code_graph_update_request SET status='BUILDING',generation_job_id=?,time_updated=NOW(3) WHERE id=?",
                    result.jobId(), updateId);
            // A fully reusable bundle is READY synchronously. Its terminal event may be
            // consumed before this transaction exposes BUILDING, so complete it here too.
            // completeUpdateForJob is status-guarded and therefore safe if the listener wins.
            if ("READY".equals(result.status())) {
                completeUpdateForJob(result.jobId(), true, null, null);
            }
            return new AppendOutcome(AppendOutcome.Kind.STARTED, updateId, result.jobId());
        } catch (RuntimeException exception) {
            markUpdateFailed(updateId, "CODE_GRAPH_APPEND_SUBMIT_FAILED", exception);
            lifecycle.event(workflowRunId, "code_graph.update.failed", Map.of(
                    "updateRequestId", updateId,
                    "errorCode", "CODE_GRAPH_APPEND_SUBMIT_FAILED"));
            throw exception;
        }
    }

    /**
     * Drain pass: invoked after a REPO_APPEND job finishes (READY or FAILED). If a
     * PENDING update exists and the frozen repository set has diverged from the new
     * ACTIVE binding, kick off the next REPO_APPEND.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void drainPendingUpdates(long workflowRunId) {
        var pending = jdbc.query("SELECT id,triggered_by FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id LIMIT 1",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, workflowRunId);
        if (pending.isEmpty()) return;
        // Someone else may already be building; double-check under lock.
        jdbc.queryForObject("SELECT id FROM workflow_run WHERE id=? FOR UPDATE", Long.class, workflowRunId);
        if (findOpenAppendJob(workflowRunId) != null) return;

        var updateId = pending.get(0)[0];
        var actorId = pending.get(0)[1];
        var inputs = frozenRepositories.inputs(workflowRunId);
        if (inputs.isEmpty()) {
            markUpdateFailed(updateId, "CODE_GRAPH_APPEND_INPUT_MISSING", null);
            return;
        }
        try {
            var result = startAppendJob(workflowRunId, actorId, updateId, inputs);
            jdbc.update("UPDATE code_graph_update_request SET status='BUILDING',generation_job_id=?,time_updated=NOW(3) WHERE id=?",
                    result.jobId(), updateId);
            if ("READY".equals(result.status())) {
                completeUpdateForJob(result.jobId(), true, null, null);
            }
            lifecycle.event(workflowRunId, "code_graph.preparation.started", Map.of(
                    "jobId", result.jobId(),
                    "updateRequestId", updateId,
                    "repositoryCount", inputs.size(),
                    "trigger", "DRAIN_PENDING_UPDATE"));
        } catch (RuntimeException exception) {
            markUpdateFailed(updateId, "CODE_GRAPH_APPEND_SUBMIT_FAILED", exception);
            lifecycle.event(workflowRunId, "code_graph.update.failed", Map.of(
                    "updateRequestId", updateId,
                    "errorCode", "CODE_GRAPH_APPEND_SUBMIT_FAILED"));
            log.warn("event=code_graph.append.drain_failed runId={} updateId={} err={}",
                    workflowRunId, updateId, exception.toString());
        }
    }

    /** Marks the update request succeeded. Called by the metadata-store observer on binding activation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeUpdateForJob(long jobId, boolean succeeded, String errorCode, String errorMessage) {
        var rows = jdbc.query("SELECT id,workflow_run_id FROM code_graph_update_request WHERE generation_job_id=? AND status='BUILDING'",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, jobId);
        if (rows.isEmpty()) return;
        var updateId = rows.get(0)[0];
        var workflowRunId = rows.get(0)[1];
        if (succeeded) {
            jdbc.update("UPDATE code_graph_update_request SET status='READY',time_updated=NOW(3) WHERE id=?", updateId);
            lifecycle.terminalUpdate(workflowRunId, jobId, true, null);
        } else {
            jdbc.update("UPDATE code_graph_update_request SET status='FAILED',last_error_code=?,last_error_message=?,time_updated=NOW(3) WHERE id=?",
                    errorCode, errorMessage == null ? null : errorMessage.substring(0, Math.min(2000, errorMessage.length())), updateId);
            lifecycle.event(workflowRunId, "code_graph.update.failed", Map.of(
                    "updateRequestId", updateId, "jobId", jobId,
                    "errorCode", errorCode == null ? "CODE_GRAPH_BUILD_FAILED" : errorCode));
            lifecycle.terminalUpdate(workflowRunId, jobId, false, errorMessage);
        }
    }

    /** Retry a FAILED update request. Only that target version is rebuilt; the ACTIVE binding is untouched. */
    @Transactional
    public long retryUpdate(long workflowRunId, long updateRequestId, long actorId) {
        requireManager(workflowRunId, actorId);
        var rows = jdbc.query("SELECT workflow_run_id,status FROM code_graph_update_request WHERE id=? AND workflow_run_id=?",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, updateRequestId, workflowRunId);
        if (rows.isEmpty()) throw new BusinessException("CODE_GRAPH_UPDATE_NOT_FOUND", "Code graph update request not found", HttpStatus.NOT_FOUND);
        var status = (String) rows.get(0)[1];
        if (!"FAILED".equals(status)) {
            throw new BusinessException("CODE_GRAPH_UPDATE_NOT_RETRYABLE", "Only FAILED update requests can be retried", HttpStatus.CONFLICT);
        }
        jdbc.queryForObject("SELECT id FROM workflow_run WHERE id=? FOR UPDATE", Long.class, workflowRunId);
        if (findOpenAppendJob(workflowRunId) != null) {
            // Re-queue as PENDING; drain will pick it up after the in-flight job finishes.
            jdbc.update("UPDATE code_graph_update_request SET status='PENDING',retry_count=retry_count+1,last_error_code=NULL,last_error_message=NULL,generation_job_id=NULL,time_updated=NOW(3) WHERE id=?",
                    updateRequestId);
            return updateRequestId;
        }
        var inputs = frozenRepositories.inputs(workflowRunId);
        if (inputs.isEmpty()) throw new BusinessException("CODE_GRAPH_REPOSITORY_NOT_FROZEN", "No frozen repository input is available", HttpStatus.CONFLICT);
        var result = startAppendJob(workflowRunId, actorId, updateRequestId, inputs);
        jdbc.update("UPDATE code_graph_update_request SET status='BUILDING',retry_count=retry_count+1,generation_job_id=?,last_error_code=NULL,last_error_message=NULL,time_updated=NOW(3) WHERE id=?",
                result.jobId(), updateRequestId);
        if ("READY".equals(result.status())) {
            completeUpdateForJob(result.jobId(), true, null, null);
        }
        return updateRequestId;
    }

    private void requireManager(long workflowRunId, long actorId) {
        var allowed = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run w JOIN virtual_project p ON p.id=w.project_id LEFT JOIN virtual_project_member m ON m.project_id=p.id AND m.user_id=? AND m.status='ACTIVE' AND m.membership_type='OWNER' WHERE w.id=? AND (p.created_by=? OR m.user_id IS NOT NULL)",
                Integer.class, actorId, workflowRunId, actorId);
        if (allowed == null || allowed == 0) {
            throw new BusinessException("CODE_GRAPH_UPDATE_FORBIDDEN", "Only the project owner can retry a code graph update", HttpStatus.FORBIDDEN);
        }
    }

    // ---- internals --------------------------------------------------------

    private void requireRunningWithActiveBinding(long workflowRunId) {
        var rows = jdbc.query("SELECT w.status,(SELECT COUNT(*) FROM workflow_run_code_graph_binding b WHERE b.workflow_run_id=w.id AND b.status='ACTIVE') FROM workflow_run w WHERE w.id=?",
                (rs, n) -> new Object[]{rs.getString(1), rs.getInt(2)}, workflowRunId);
        if (rows.isEmpty()) throw new BusinessException("WORKFLOW_RUN_NOT_FOUND", "Workflow run not found", HttpStatus.NOT_FOUND);
        var status = (String) rows.get(0)[0];
        var activeBindings = (Integer) rows.get(0)[1];
        if (!"RUNNING".equals(status)) {
            throw new BusinessException("CODE_GRAPH_APPEND_NOT_ALLOWED",
                    "Repositories can only be appended while the workflow is RUNNING", HttpStatus.CONFLICT);
        }
        if (activeBindings == null || activeBindings == 0) {
            throw new BusinessException("CODE_GRAPH_APPEND_NOT_ALLOWED",
                    "Workflow has no ACTIVE code graph binding", HttpStatus.CONFLICT);
        }
    }

    /** Freeze only the newly appended rows. Never touches existing frozen rows. */
    private void freezeAppended(long workflowRunId, List<Long> appendedIds) {
        // Reuse the same remote freeze logic row-by-row so we never re-resolve existing ones.
        var rows = jdbc.query("SELECT id,normalized_url,tracked_branch,resolved_commit_sha FROM workflow_run_git_repository WHERE workflow_run_id=? AND id IN (" +
                        placeholders(appendedIds.size()) + ") AND status='ACTIVE'",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                merge(workflowRunId, appendedIds));
        for (var row : rows) {
            if (row[3] != null) continue; // already frozen — never advance
            var frozen = frozenRepositories.freezeOne((Long) row[0], (String) row[1], (String) row[2]);
            if (frozen == null) {
                throw new BusinessException("CODE_GRAPH_REPOSITORY_NOT_FROZEN",
                        "Failed to freeze appended repository id=" + row[0], HttpStatus.CONFLICT);
            }
        }
    }

    private Long findOpenAppendJob(long workflowRunId) {
        var ids = jdbc.query("SELECT id FROM code_graph_generation_job WHERE workflow_run_id=? AND job_type='REPO_APPEND' AND status IN ('PENDING','PLANNING','BUILDING','COMPOSING','SEMANTIC_INDEXING') ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getLong(1), workflowRunId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long recordPendingUpdate(long workflowRunId, long actorId, List<Long> appendedIds) {
        var inputs = frozenRepositories.inputs(workflowRunId);
        var hash = repositorySetHash(inputs);
        var existing = jdbc.query("SELECT id,appended_workflow_repository_ids_json FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id LIMIT 1 FOR UPDATE",
                (rs,n)->new Object[]{rs.getLong(1),rs.getString(2)}, workflowRunId);
        if (!existing.isEmpty()) {
            var merged = new java.util.LinkedHashSet<Long>();
            try { merged.addAll(mapper.readValue((String) existing.get(0)[1], mapper.getTypeFactory().constructCollectionType(List.class, Long.class))); }
            catch (Exception ignored) { }
            merged.addAll(appendedIds);
            long id = (Long) existing.get(0)[0];
            jdbc.update("UPDATE code_graph_update_request SET target_repository_set_hash=?,target_repository_count=?,triggered_by=?,appended_workflow_repository_ids_json=CAST(? AS JSON),time_updated=NOW(3) WHERE id=? AND status='PENDING'",
                    hash, inputs.size(), actorId, json(merged), id);
            return id;
        }
        jdbc.update("INSERT INTO code_graph_update_request(time_created,time_updated,workflow_run_id,target_repository_set_hash,target_repository_count,triggered_by,status,appended_workflow_repository_ids_json) VALUES(NOW(3),NOW(3),?,?,?,?,'PENDING',CAST(? AS JSON))",
                workflowRunId, hash, inputs.size(), actorId, json(appendedIds));
        return jdbc.queryForObject("SELECT id FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id DESC LIMIT 1", Long.class, workflowRunId);
    }

    private CodeGraphGenerationCoordinator.StartResult startAppendJob(long workflowRunId, long actorId, long updateId,
                                                                       List<RepositoryInput> inputs) {
        var command = new GenerationCommand(workflowRunId, "REPO_APPEND",
                properties.engineType(), properties.engineVersion(), properties.adapterVersion(),
                properties.engineConfigHash(), properties.groupConfigHash(), inputs);
        var result = preparation.prepare(actorId, command);
        lifecycle.event(workflowRunId, "code_graph.preparation.started", Map.of(
                "jobId", result.jobId(), "updateRequestId", updateId,
                "repositoryCount", inputs.size(), "jobType", "REPO_APPEND"));
        return result;
    }

    private void markUpdateFailed(long updateId, String code, RuntimeException exception) {
        var message = exception == null || exception.getMessage() == null ? null
                : exception.getMessage().substring(0, Math.min(2000, exception.getMessage().length()));
        jdbc.update("UPDATE code_graph_update_request SET status='FAILED',last_error_code=?,last_error_message=?,time_updated=NOW(3) WHERE id=?",
                code, message, updateId);
    }

    private String repositorySetHash(List<RepositoryInput> repositories) {
        var sorted = repositories.stream().map(r -> r.repositoryKey()+":"+r.commitSha()+":"+r.treeSha()).sorted().toList();
        var joined = String.join("|", sorted);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String placeholders(int n) {
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    private static Object[] merge(long workflowRunId, List<Long> ids) {
        var out = new ArrayList<Object>(ids.size() + 1);
        out.add(workflowRunId);
        out.addAll(ids);
        return out.toArray();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Failed to serialise update payload", exception); }
    }

    public record AppendOutcome(Kind kind, long updateRequestId, Long jobId) {
        public enum Kind { STARTED, QUEUED }
    }
}

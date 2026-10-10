package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.agentworkflow.application.AgentWorkflowService;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkflowProperties;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.git.application.GitWorkflowRepositoryFreezeService;
import com.company.skillplatform.git.application.ProjectGitRepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates M3's two-phase workflow start: freeze/build first, create Agent runs only after activation. */
@Service
public class WorkflowCodeGraphService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowCodeGraphService.class);
    private final AgentWorkflowService workflows;
    private final ProjectGitRepositoryService projectRepositories;
    private final GitWorkflowRepositoryFreezeService frozenRepositories;
    private final CodeGraphPreparationService preparation;
    private final CodeGraphStatusService status;
    private final CodeGraphWorkflowProperties properties;
    private final JdbcTemplate jdbc;

    public WorkflowCodeGraphService(AgentWorkflowService workflows,
                                    ProjectGitRepositoryService projectRepositories,
                                    GitWorkflowRepositoryFreezeService frozenRepositories,
                                    CodeGraphPreparationService preparation,
                                    CodeGraphStatusService status,
                                    CodeGraphWorkflowProperties properties,
                                    JdbcTemplate jdbc) {
        this.workflows = workflows;
        this.projectRepositories = projectRepositories;
        this.frozenRepositories = frozenRepositories;
        this.preparation = preparation;
        this.status = status;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public PrepareResult prepare(String projectKey, long actorId, String initialRequest, String contextSnapshotJson) {
        var run = workflows.prepareConfigured(projectKey, actorId, initialRequest, contextSnapshotJson);
        if ("RUNNING".equals(run.status()) || "READY_TO_START".equals(run.status()) || hasOpenJob(run.id())) {
            return result(run.id(), actorId);
        }
        try {
            projectRepositories.snapshotForRun(run.id(), run.projectId(), actorId);
            frozenRepositories.freeze(run.id());
            start(run.id(), actorId, "INITIAL", false);
        } catch (RuntimeException exception) {
            failBeforeSubmission(run.id(), exception);
        }
        return result(run.id(), actorId);
    }

    public CodeGraphStatusService.View retry(long runId, long actorId) {
        workflows.requireRunManager(runId, actorId);
        var workflowStatus = workflowStatus(runId);
        if (!"CODE_GRAPH_PREPARATION_FAILED".equals(workflowStatus)) {
            throw new BusinessException("CODE_GRAPH_RETRY_NOT_ALLOWED", "Only a failed code graph preparation can be retried", HttpStatus.CONFLICT);
        }
        jdbc.update("UPDATE workflow_run SET status='PREPARING_CODE_GRAPH',failure_reason=NULL,time_updated=NOW(3) WHERE id=?", runId);
        try {
            frozenRepositories.freeze(runId);
            start(runId, actorId, "MANUAL_RETRY", false);
        } catch (RuntimeException exception) {
            failBeforeSubmission(runId, exception);
        }
        return status.get(runId, actorId);
    }

    /**
     * Debug-only rebuild for a prepared run. This deliberately does not reuse
     * the failure retry contract: it creates a new binding from the same frozen
     * repositories while no Agent stage has been activated yet.
     */
    public CodeGraphStatusService.View debugRebuild(long runId, long actorId) {
        if (!properties.debugRebuildEnabled()) {
            throw new BusinessException("CODE_GRAPH_DEBUG_REBUILD_DISABLED", "Debug code graph rebuild is disabled", HttpStatus.NOT_FOUND);
        }
        workflows.requireRunManager(runId, actorId);
        if (!"READY_TO_START".equals(workflowStatus(runId))) {
            throw new BusinessException("CODE_GRAPH_REBUILD_NOT_ALLOWED", "Code graph rebuild is only allowed before workflow activation", HttpStatus.CONFLICT);
        }
        Integer agentRuns = jdbc.queryForObject("SELECT COUNT(*) FROM agent_workflow_run ar JOIN stage_run s ON s.id=ar.stage_run_id WHERE s.workflow_run_id=?", Integer.class, runId);
        if (agentRuns != null && agentRuns > 0) {
            throw new BusinessException("CODE_GRAPH_REBUILD_NOT_ALLOWED", "Workflow stages have already been created", HttpStatus.CONFLICT);
        }
        jdbc.update("UPDATE workflow_run SET status='PREPARING_CODE_GRAPH',failure_reason=NULL,time_updated=NOW(3) WHERE id=? AND status='READY_TO_START'", runId);
        try {
            frozenRepositories.freeze(runId);
            start(runId, actorId, "DEBUG_REBUILD", true);
        } catch (RuntimeException exception) {
            failBeforeSubmission(runId, exception);
        }
        return status.get(runId, actorId);
    }

    /**
     * Activates a prepared workflow run.
     *
     * <p><b>Gate contract (M6 §20):</b> activation is gated <em>only</em> on the structural
     * code graph being READY — expressed as {@code workflow_run.status = 'READY_TO_START'}.
     * The semantic sidecar status ({@code semantic_index_status} on the active binding) is
     * deliberately <em>not</em> consulted here: {@code DEGRADED} (Qdrant failed) and
     * {@code INDEXING} (still building) runs must both start, with Agent preload falling
     * back to structural queries until the sidecar recovers. See the design doc section
     * "Qdrant 是否阻塞 Workflow".</p>
     */
    public AgentWorkflowService.RunView activate(long runId, long actorId) {
        workflows.requireRunManager(runId, actorId);
        if (!"READY_TO_START".equals(workflowStatus(runId))) return workflows.activatePrepared(runId, actorId);
        logSemanticStatus(runId); // observability only — never blocks activation
        if (frozenRepositories.isFresh(runId)) return workflows.activatePrepared(runId, actorId);

        jdbc.update("UPDATE workflow_run SET status='PREPARING_CODE_GRAPH',failure_reason=NULL,time_updated=NOW(3) WHERE id=? AND status='READY_TO_START'", runId);
        try {
            frozenRepositories.freeze(runId);
            start(runId, actorId, "START_FRESHNESS_REFRESH", false);
        } catch (RuntimeException exception) {
            failBeforeSubmission(runId, exception);
        }
        return workflows.run(runId, actorId);
    }

    private void logSemanticStatus(long runId) {
        try {
            var rows = jdbc.query("SELECT semantic_index_status FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND status='ACTIVE' LIMIT 1",
                    (rs, n) -> rs.getString(1), runId);
            if (rows.isEmpty()) return;
            var status = rows.get(0);
            if (!"READY".equals(status)) {
                log.info("event=code_graph.activate.semantic_{} runId={}", status == null ? "unknown" : status.toLowerCase(), runId);
            }
        } catch (RuntimeException ignored) { /* observability must never break activation */ }
    }

    private void start(long runId, long actorId, String reason, boolean forceRebuild) {
        var inputs = frozenRepositories.inputs(runId);
        if (inputs.isEmpty()) throw new BusinessException("CODE_GRAPH_REPOSITORY_NOT_FROZEN", "No frozen repository input is available", HttpStatus.CONFLICT);
        var command = new GenerationCommand(runId, reason, properties.engineType(), properties.engineVersion(),
                properties.adapterVersion(), properties.engineConfigHash(), properties.groupConfigHash(), inputs);
        if (forceRebuild) preparation.prepare(actorId, command, true);
        else preparation.prepare(actorId, command);
    }

    private boolean hasOpenJob(long runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM code_graph_generation_job WHERE workflow_run_id=? AND status IN ('BUILDING','READY')", Integer.class, runId);
        return count != null && count > 0;
    }

    private String workflowStatus(long runId) {
        var values = jdbc.query("SELECT status FROM workflow_run WHERE id=?", (rs, row) -> rs.getString(1), runId);
        if (values.isEmpty()) throw new BusinessException("WORKFLOW_RUN_NOT_FOUND", "Workflow run not found", HttpStatus.NOT_FOUND);
        return values.get(0);
    }

    @Transactional
    protected void failBeforeSubmission(long runId, RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        jdbc.update("UPDATE workflow_run SET status='CODE_GRAPH_PREPARATION_FAILED',failure_reason=?,time_updated=NOW(3) WHERE id=? AND status='PREPARING_CODE_GRAPH'",
                message.substring(0, Math.min(2000, message.length())), runId);
    }

    private PrepareResult result(long runId, long actorId) {
        var value = status.get(runId, actorId);
        return new PrepareResult(runId, runId, value.status(), value.jobId(), value);
    }

    public record PrepareResult(long runId, long workflowRunId, String status, Long jobId,
                                CodeGraphStatusService.View codeGraph) {}
}

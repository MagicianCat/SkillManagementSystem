package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.agentworkflow.application.AgentWorkflowService;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkflowProperties;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.git.application.GitWorkflowRepositoryFreezeService;
import com.company.skillplatform.git.application.ProjectGitRepositoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates M3's two-phase workflow start: freeze/build first, create Agent runs only after activation. */
@Service
public class WorkflowCodeGraphService {
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
            start(run.id(), actorId, "INITIAL");
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
            start(runId, actorId, "MANUAL_RETRY");
        } catch (RuntimeException exception) {
            failBeforeSubmission(runId, exception);
        }
        return status.get(runId, actorId);
    }

    public AgentWorkflowService.RunView activate(long runId, long actorId) {
        workflows.requireRunManager(runId, actorId);
        if (!"READY_TO_START".equals(workflowStatus(runId))) return workflows.activatePrepared(runId, actorId);
        if (frozenRepositories.isFresh(runId)) return workflows.activatePrepared(runId, actorId);

        jdbc.update("UPDATE workflow_run SET status='PREPARING_CODE_GRAPH',failure_reason=NULL,time_updated=NOW(3) WHERE id=? AND status='READY_TO_START'", runId);
        try {
            frozenRepositories.freeze(runId);
            start(runId, actorId, "START_FRESHNESS_REFRESH");
        } catch (RuntimeException exception) {
            failBeforeSubmission(runId, exception);
        }
        return workflows.run(runId, actorId);
    }

    private void start(long runId, long actorId, String reason) {
        var inputs = frozenRepositories.inputs(runId);
        if (inputs.isEmpty()) throw new BusinessException("CODE_GRAPH_REPOSITORY_NOT_FROZEN", "No frozen repository input is available", HttpStatus.CONFLICT);
        preparation.prepare(actorId, new GenerationCommand(runId, reason, properties.engineType(), properties.engineVersion(),
                properties.adapterVersion(), properties.engineConfigHash(), properties.groupConfigHash(), inputs));
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

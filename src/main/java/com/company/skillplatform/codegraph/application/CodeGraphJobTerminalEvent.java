package com.company.skillplatform.codegraph.application;

import org.springframework.context.ApplicationEvent;

/**
 * M7: published whenever a {@code code_graph_generation_job} transitions to a terminal
 * state (READY or FAILED). Decouples the {@code CodeGraphGenerationCoordinator} from
 * post-completion follow-ups — notably draining the {@code code_graph_update_request}
 * queue and emitting per-update terminal notifications for REPO_APPEND jobs.
 */
public class CodeGraphJobTerminalEvent extends ApplicationEvent {
    private final long jobId;
    private final long workflowRunId;
    private final String jobType;
    private final boolean succeeded;
    private final String errorCode;
    private final String errorMessage;

    public CodeGraphJobTerminalEvent(Object source, long jobId, long workflowRunId, String jobType,
                                     boolean succeeded, String errorCode, String errorMessage) {
        super(source);
        this.jobId = jobId;
        this.workflowRunId = workflowRunId;
        this.jobType = jobType;
        this.succeeded = succeeded;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public long jobId() { return jobId; }
    public long workflowRunId() { return workflowRunId; }
    public String jobType() { return jobType; }
    public boolean succeeded() { return succeeded; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
}

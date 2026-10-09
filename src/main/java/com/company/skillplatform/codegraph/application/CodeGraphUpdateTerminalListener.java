package com.company.skillplatform.codegraph.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

/**
 * M7: reacts to a {@link CodeGraphJobTerminalEvent} after a REPO_APPEND generation
 * completes. Responsibilities:
 *
 * <ol>
 *   <li>Mark the matching {@code code_graph_update_request} READY / FAILED and emit the
 *       user-facing {@code CODE_GRAPH_UPDATED} notification.</li>
 *   <li>Drain the next PENDING update request, if any, so consecutive appends merge
 *       into a single follow-up build.</li>
 * </ol>
 *
 * Fires after the surrounding transaction commits so a drain that fails can never roll
 * back the binding swap. Async so a slow drain does not block the poller thread.
 */
@Component
public class CodeGraphUpdateTerminalListener {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphUpdateTerminalListener.class);

    private final JdbcTemplate jdbc;
    private final WorkflowCodeGraphAppendService appendService;

    public CodeGraphUpdateTerminalListener(JdbcTemplate jdbc, WorkflowCodeGraphAppendService appendService) {
        this.jdbc = jdbc;
        this.appendService = appendService;
    }

    @Async
    @EventListener
    public void onTerminal(CodeGraphJobTerminalEvent event) {
        try {
            if (!isRepoAppend(event.jobId())) return;
            appendService.completeUpdateForJob(event.jobId(), event.succeeded(), event.errorCode(), event.errorMessage());
            appendService.drainPendingUpdates(event.workflowRunId());
        } catch (RuntimeException exception) {
            log.warn("event=code_graph.update.terminal_handler_failed jobId={} err={}", event.jobId(), exception.toString());
        }
    }

    private boolean isRepoAppend(long jobId) {
        try {
            var rows = jdbc.query("SELECT job_type FROM code_graph_generation_job WHERE id=?",
                    (rs, n) -> rs.getString(1), jobId);
            return !rows.isEmpty() && "REPO_APPEND".equals(rows.get(0));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

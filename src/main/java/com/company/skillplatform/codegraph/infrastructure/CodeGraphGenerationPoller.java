package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.application.CodeGraphGenerationCoordinator;
import com.company.skillplatform.codegraph.application.CodeGraphMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CodeGraphGenerationPoller {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphGenerationPoller.class);
    private final JdbcTemplate jdbc;
    private final CodeGraphGenerationCoordinator coordinator;
    private final CodeGraphMetadataStore store;
    private final CodeGraphWorkerProperties properties;
    private final String owner = UUID.randomUUID().toString();

    public CodeGraphGenerationPoller(JdbcTemplate jdbc, CodeGraphGenerationCoordinator coordinator,
                                     CodeGraphMetadataStore store, CodeGraphWorkerProperties properties) {
        this.jdbc = jdbc; this.coordinator = coordinator; this.store = store; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${skill-platform.code-graph.worker.poll-interval:PT2S}")
    public void poll() {
        if (!properties.enabled()) return;
        var ids = jdbc.query("SELECT id FROM code_graph_generation_job WHERE status='BUILDING' AND engine_job_id IS NOT NULL AND (next_attempt_at IS NULL OR next_attempt_at<=NOW(3)) AND (lease_until IS NULL OR lease_until<NOW(3)) ORDER BY time_created,id LIMIT ?",
                (rs, row) -> rs.getLong(1), properties.maxConcurrentBuilds());
        for (var id : ids) {
            if (jdbc.update("UPDATE code_graph_generation_job SET lease_until=DATE_ADD(NOW(3),INTERVAL 30 SECOND),time_updated=NOW(3) WHERE id=? AND status='BUILDING' AND (lease_until IS NULL OR lease_until<NOW(3))", id) == 0) continue;
            try {
                var result = coordinator.poll(id);
                if ("BUILDING".equals(result.status())) jdbc.update("UPDATE code_graph_generation_job SET lease_until=NULL,next_attempt_at=DATE_ADD(NOW(3),INTERVAL 2 SECOND),time_updated=NOW(3) WHERE id=? AND status='BUILDING'", id);
            } catch (RuntimeException exception) {
                var retry = jdbc.queryForObject("SELECT retry_count+1 FROM code_graph_generation_job WHERE id=?", Integer.class, id);
                var retryable = !(exception instanceof CodeGraphWorkerClient.CodeGraphWorkerException worker) || worker.retryable();
                if (!retryable || (retry != null && retry >= 3)) store.fail(id, "CODE_GRAPH_WORKER_POLL_FAILED", safe(exception));
                else jdbc.update("UPDATE code_graph_generation_job SET retry_count=retry_count+1,lease_until=NULL,next_attempt_at=DATE_ADD(NOW(3),INTERVAL POW(2,retry_count+1) SECOND),last_error_code='CODE_GRAPH_WORKER_POLL_FAILED',last_error_message=?,time_updated=NOW(3) WHERE id=? AND status='BUILDING'", safe(exception), id);
                log.warn("event=code_graph.poll_failed jobId={} owner={} attempt={}", id, owner, retry);
            }
        }
    }

    private String safe(RuntimeException exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.substring(0, Math.min(message.length(), 1000));
    }
}

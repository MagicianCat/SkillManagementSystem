package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CodeGraphStatusService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CodeGraphStatusService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional(readOnly = true)
    public View get(long runId, long actorId) {
        var run = access(runId, actorId);
        var jobs = jdbc.queryForList("SELECT j.id,j.status,j.progress,j.reason,j.job_type,j.retry_count,j.last_error_code,j.last_error_message,j.reuse_plan_json,j.time_created,j.completed_at,b.id binding_id,b.version_no binding_version,b.status binding_status,b.semantic_index_status,g.id bundle_id,g.repository_count FROM code_graph_generation_job j JOIN workflow_run_code_graph_binding b ON b.id=j.target_binding_id LEFT JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE j.workflow_run_id=? ORDER BY j.id DESC LIMIT 1", runId);
        var repositoryCount = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run_git_repository WHERE workflow_run_id=? AND status='ACTIVE'", Integer.class, runId);
        var activeBinding = activeBinding(runId);
        var preparingBinding = preparingBinding(runId);
        var pendingUpdate = pendingUpdate(runId);
        var lastUpdateError = lastUpdateError(runId);
        if (jobs.isEmpty()) return new View(runId, String.valueOf(run.get("status")), null, null, 0,
                failed(run) ? "CODE_GRAPH_PREPARATION_FAILED" : null, string(run.get("failure_reason")), List.of(), failed(run), repositoryCount == null ? 0 : repositoryCount,
                activeBinding, preparingBinding, pendingUpdate, lastUpdateError);
        var row = jobs.get(0);
        var status = String.valueOf(row.get("status"));
        var workflowFailed = failed(run);
        return new View(runId, String.valueOf(run.get("status")), number(row.get("id")), status,
                ((Number) row.get("progress")).intValue(), workflowFailed ? "CODE_GRAPH_PREPARATION_FAILED" : string(row.get("last_error_code")),
                workflowFailed && run.get("failure_reason") != null ? string(run.get("failure_reason")) : string(row.get("last_error_message")),
                repositories(string(row.get("reuse_plan_json")), status), workflowFailed || "FAILED".equals(status), repositoryCount == null ? 0 : repositoryCount,
                activeBinding, preparingBinding, pendingUpdate, lastUpdateError);
    }

    private Map<String, Object> access(long runId, long actorId) {
        var rows = jdbc.queryForList("SELECT w.status,w.project_id,w.failure_reason FROM workflow_run w JOIN virtual_project_member m ON m.project_id=w.project_id AND m.user_id=? AND m.status='ACTIVE' WHERE w.id=?", actorId, runId);
        if (rows.isEmpty()) throw new BusinessException("WORKFLOW_RUN_NOT_FOUND", "Workflow run not found", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private List<RepositoryProgress> repositories(String json, String jobStatus) {
        if (json == null) return List.of();
        try {
            JsonNode root = mapper.readTree(json).path("repositories");
            var result = new ArrayList<RepositoryProgress>();
            for (JsonNode node : root) {
                var decision = node.path("decision").asText("FULL_REQUIRED");
                var state = "REUSE_EXACT".equals(decision) ? "REUSED" : "READY".equals(jobStatus) ? "READY" : "FAILED".equals(jobStatus) ? "FAILED" : "BUILDING";
                var repository = node.path("repository");
                result.add(new RepositoryProgress(repository.path("logicalName").asText(),
                        repository.path("repositoryKey").asText(null), decision, state));
            }
            return result;
        } catch (Exception ignored) { return List.of(); }
    }

    private BindingSummary activeBinding(long runId) {
        var rows = jdbc.query("SELECT b.id,b.version_no,b.status,b.semantic_index_status,b.activated_at,g.id,g.repository_count FROM workflow_run_code_graph_binding b LEFT JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE b.workflow_run_id=? AND b.status='ACTIVE' ORDER BY b.version_no DESC LIMIT 1",
                (rs, n) -> new BindingSummary(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                        rs.getObject(6, Long.class), rs.getObject(7, Integer.class)), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BindingSummary preparingBinding(long runId) {
        var rows = jdbc.query("SELECT b.id,b.version_no,b.status,b.semantic_index_status,b.activated_at,g.id,g.repository_count FROM workflow_run_code_graph_binding b LEFT JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE b.workflow_run_id=? AND b.status='PREPARING' ORDER BY b.version_no DESC LIMIT 1",
                (rs, n) -> new BindingSummary(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                        rs.getObject(6, Long.class), rs.getObject(7, Integer.class)), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PendingUpdate pendingUpdate(long runId) {
        var rows = jdbc.query("SELECT id,status,retry_count,last_error_code,last_error_message,target_repository_count,time_created FROM code_graph_update_request WHERE workflow_run_id=? AND status IN ('PENDING','BUILDING') ORDER BY id DESC LIMIT 1",
                (rs, n) -> new PendingUpdate(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getTimestamp(7).toInstant()), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UpdateError lastUpdateError(long runId) {
        var rows = jdbc.query("SELECT id,last_error_code,last_error_message FROM code_graph_update_request WHERE workflow_run_id=? AND status='FAILED' ORDER BY time_updated DESC LIMIT 1",
                (rs, n) -> new UpdateError(rs.getLong(1), rs.getString(2), rs.getString(3)), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean failed(Map<String, Object> run) { return "CODE_GRAPH_PREPARATION_FAILED".equals(String.valueOf(run.get("status"))); }

    public record View(long workflowRunId, String status, Long jobId, String jobStatus, int progress,
                       String errorCode, String errorMessage, List<RepositoryProgress> repositories, boolean retryable,
                       int repositoryCount,
                       BindingSummary activeBinding, BindingSummary preparingBinding,
                       PendingUpdate pendingUpdate, UpdateError lastUpdateError) {}
    public record RepositoryProgress(String name, String repositoryKey, String reuseDecision, String status) {}
    public record BindingSummary(long bindingId, int version, String status, String semanticIndexStatus,
                                 java.time.Instant activatedAt, Long bundleId, Integer repositoryCount) {}
    public record PendingUpdate(long updateRequestId, String status, int retryCount,
                                String lastErrorCode, String lastErrorMessage,
                                int targetRepositoryCount, java.time.Instant queuedAt) {}
    public record UpdateError(long updateRequestId, String errorCode, String errorMessage) {}
}

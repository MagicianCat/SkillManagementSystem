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
        var jobs = jdbc.queryForList("SELECT j.id,j.status,j.progress,j.reason,j.retry_count,j.last_error_code,j.last_error_message,j.reuse_plan_json,j.time_created,j.completed_at,b.id binding_id,b.status binding_status,b.semantic_index_status,g.id bundle_id,g.repository_count FROM code_graph_generation_job j JOIN workflow_run_code_graph_binding b ON b.id=j.target_binding_id LEFT JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE j.workflow_run_id=? ORDER BY j.id DESC LIMIT 1", runId);
        var repositoryCount = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run_git_repository WHERE workflow_run_id=? AND status='ACTIVE'", Integer.class, runId);
        if (jobs.isEmpty()) return new View(runId, String.valueOf(run.get("status")), null, null, 0,
                failed(run) ? "CODE_GRAPH_PREPARATION_FAILED" : null, string(run.get("failure_reason")), List.of(), failed(run), repositoryCount == null ? 0 : repositoryCount);
        var row = jobs.get(0);
        var status = String.valueOf(row.get("status"));
        var workflowFailed = failed(run);
        return new View(runId, String.valueOf(run.get("status")), number(row.get("id")), status,
                ((Number) row.get("progress")).intValue(), workflowFailed ? "CODE_GRAPH_PREPARATION_FAILED" : string(row.get("last_error_code")),
                workflowFailed && run.get("failure_reason") != null ? string(run.get("failure_reason")) : string(row.get("last_error_message")),
                repositories(string(row.get("reuse_plan_json")), status), workflowFailed || "FAILED".equals(status), repositoryCount == null ? 0 : repositoryCount);
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
                result.add(new RepositoryProgress(node.path("repository").path("logicalName").asText(), decision, state));
            }
            return result;
        } catch (Exception ignored) { return List.of(); }
    }

    private Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean failed(Map<String, Object> run) { return "CODE_GRAPH_PREPARATION_FAILED".equals(String.valueOf(run.get("status"))); }

    public record View(long workflowRunId, String status, Long jobId, String jobStatus, int progress,
                       String errorCode, String errorMessage, List<RepositoryProgress> repositories, boolean retryable,
                       int repositoryCount) {}
    public record RepositoryProgress(String name, String reuseDecision, String status) {}
}

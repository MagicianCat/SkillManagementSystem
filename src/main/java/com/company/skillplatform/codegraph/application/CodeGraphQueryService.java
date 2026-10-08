package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort.GraphRef;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort.GraphRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves an immutable workflow binding before delegating a safe, structured query to the worker. */
@Service
public class CodeGraphQueryService {
    private final JdbcTemplate jdbc;
    private final CodeGraphEnginePort engine;
    private final ObjectMapper mapper;

    public CodeGraphQueryService(JdbcTemplate jdbc, CodeGraphEnginePort engine, ObjectMapper mapper) {
        this.jdbc = jdbc; this.engine = engine; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Overview overview(long runId, long actorId) {
        var graph = resolve(runId, actorId, null);
        var repositories = jdbc.query("SELECT br.repository_alias, s.logical_repository_key, s.commit_sha, s.tree_sha, br.build_mode FROM code_graph_bundle_repository br JOIN code_graph_repository_snapshot s ON s.id=br.repository_snapshot_id WHERE br.bundle_id=? ORDER BY br.repository_alias", (rs, row) ->
                new RepositoryOverview(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), graph.bundleId());
        var engineOverview = engine.query(new CodeGraphEnginePort.QueryRequest("overview", graph.reference(), null, Map.of())).data();
        return new Overview(runId, graph.bindingId(), graph.version(), graph.engineType(), repositories, engineOverview);
    }

    @Transactional(readOnly = true)
    public QueryResult execute(long runId, long actorId, String operation, String repository, Map<String, Object> parameters) {
        var graph = resolve(runId, actorId, null);
        return executeResolved(runId, operation, repository, parameters, graph);
    }

    /** Agent-only entry point. The binding is read from the immutable agent_workflow_run snapshot. */
    @Transactional(readOnly = true)
    public QueryResult executeForAgent(long agentRunId, long actorId, String operation, String repository, Map<String, Object> parameters) {
        var binding = agentBinding(agentRunId, actorId);
        var graph = resolve(binding.workflowRunId(), actorId, binding.bindingId());
        return executeResolved(binding.workflowRunId(), operation, repository, parameters, graph);
    }

    /** Safe binding metadata for manifest construction; artifact location and checksum never leave this service. */
    @Transactional(readOnly = true)
    public AgentGraphInfo describeForAgent(long agentRunId, long actorId) {
        var binding = agentBinding(agentRunId, actorId);
        var graph = resolve(binding.workflowRunId(), actorId, binding.bindingId());
        return new AgentGraphInfo(binding.workflowRunId(), graph.bindingId(), graph.version(), graph.engineType(),
                graph.repositories().stream().map(GraphRepository::logicalName).toList());
    }

    @Transactional(readOnly = true)
    public AgentOverview overviewForAgent(long agentRunId, long actorId) {
        var binding = agentBinding(agentRunId, actorId);
        var graph = resolve(binding.workflowRunId(), actorId, binding.bindingId());
        var data = engine.query(new CodeGraphEnginePort.QueryRequest("overview", graph.reference(), null, Map.of())).data();
        return new AgentOverview(binding.workflowRunId(), graph.bindingId(), graph.version(), graph.engineType(), data);
    }

    private AgentBinding agentBinding(long agentRunId, long actorId) {
        var run = jdbc.query("SELECT sr.workflow_run_id,ar.code_graph_binding_id FROM agent_workflow_run ar JOIN stage_run sr ON sr.id=ar.stage_run_id JOIN workflow_run w ON w.id=sr.workflow_run_id JOIN virtual_project_member m ON m.project_id=w.project_id AND m.user_id=? AND m.status='ACTIVE' WHERE ar.id=?", (rs, n) -> new AgentBinding(rs.getLong(1), rs.getObject(2, Long.class)), actorId, agentRunId);
        if (run.isEmpty() || run.get(0).bindingId() == null) throw new BusinessException("CODE_GRAPH_NOT_BOUND", "Agent run has no frozen code graph binding", HttpStatus.NOT_FOUND);
        return run.get(0);
    }

    private QueryResult executeResolved(long runId, String operation, String repository, Map<String, Object> parameters, GraphContext graph) {
        var allowedRepository = repository == null || repository.isBlank() ? null : repository.trim();
        if (allowedRepository != null && graph.repositories().stream().noneMatch(r -> r.logicalName().equals(allowedRepository)))
            throw new BusinessException("CODE_GRAPH_REPOSITORY_NOT_FOUND", "Repository is not in this workflow graph", HttpStatus.NOT_FOUND);
        var result = engine.query(new CodeGraphEnginePort.QueryRequest(operation, graph.reference(), allowedRepository,
                sanitize(operation, parameters)));
        return new QueryResult(runId, result.operation(), result.data());
    }

    private Map<String, Object> sanitize(String operation, Map<String, Object> input) {
        var source = input == null ? Map.<String, Object>of() : input;
        var result = new LinkedHashMap<String, Object>();
        // Only structured selector fields are accepted. Artifact, SQL/Cypher and URI fields never cross this boundary.
        switch (operation.toLowerCase()) {
            case "query", "search" -> putString(result, source, "query", 500);
            case "context", "node" -> putSelector(result, source, "target");
            case "impact" -> { putSelector(result, source, "target"); putEnum(result, source, "direction", List.of("UPSTREAM", "DOWNSTREAM")); putInt(result, source, "depth", 1, 10); putInt(result, source, "limit", 1, 500); }
            case "trace" -> { putSelector(result, source, "from"); putSelector(result, source, "to"); putInt(result, source, "maxDepth", 1, 20); }
            case "route-map" -> putInt(result, source, "limit", 1, 500);
            default -> throw new BusinessException("CODE_GRAPH_QUERY_INVALID", "Unsupported code graph query", HttpStatus.BAD_REQUEST);
        }
        if (source.containsKey("limit") && !result.containsKey("limit")) putInt(result, source, "limit", 1, 100);
        return result;
    }

    private void putSelector(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) throw new BusinessException("CODE_GRAPH_QUERY_INVALID", key + " is required", HttpStatus.BAD_REQUEST);
        var clean = new LinkedHashMap<String, Object>();
        putString(clean, map, "uid", 200); putString(clean, map, "name", 300); putString(clean, map, "kind", 80); putString(clean, map, "filePath", 500);
        if (clean.isEmpty()) throw new BusinessException("CODE_GRAPH_QUERY_INVALID", key + " selector is empty", HttpStatus.BAD_REQUEST);
        target.put(key, clean);
    }
    private void putString(Map<String, Object> target, Map<?, ?> source, String key, int max) {
        var value = source.get(key);
        if (value != null) { var text = String.valueOf(value).trim(); if (text.length() > max) throw new BusinessException("CODE_GRAPH_QUERY_INVALID", key + " is too long", HttpStatus.BAD_REQUEST); if (!text.isBlank()) target.put(key, text); }
    }
    private void putEnum(Map<String, Object> target, Map<?, ?> source, String key, List<String> values) {
        putString(target, source, key, 30); if (target.containsKey(key) && !values.contains(target.get(key))) throw new BusinessException("CODE_GRAPH_QUERY_INVALID", key + " is invalid", HttpStatus.BAD_REQUEST);
    }
    private void putInt(Map<String, Object> target, Map<?, ?> source, String key, int min, int max) {
        var value = source.get(key); if (value == null) return; if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max) throw new BusinessException("CODE_GRAPH_QUERY_INVALID", key + " is out of range", HttpStatus.BAD_REQUEST); target.put(key, n.intValue());
    }

    private GraphContext resolve(long runId, long actorId, Long pinnedBindingId) {
        var rows = pinnedBindingId == null
                ? jdbc.query("SELECT b.id,b.version_no,b.engine_type,g.id,g.artifact_key,g.artifact_sha256 FROM workflow_run w JOIN virtual_project_member m ON m.project_id=w.project_id AND m.user_id=? AND m.status='ACTIVE' JOIN workflow_run_code_graph_binding b ON b.workflow_run_id=w.id LEFT JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE w.id=? AND b.status IN ('ACTIVE','SUPERSEDED') ORDER BY CASE WHEN b.status='ACTIVE' THEN 0 ELSE 1 END,b.version_no DESC LIMIT 1", (rs, row) -> new BindingRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)), actorId, runId)
                : jdbc.query("SELECT b.id,b.version_no,b.engine_type,g.id,g.artifact_key,g.artifact_sha256 FROM workflow_run w JOIN virtual_project_member m ON m.project_id=w.project_id AND m.user_id=? AND m.status='ACTIVE' JOIN workflow_run_code_graph_binding b ON b.workflow_run_id=w.id AND b.id=? LEFT JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE w.id=? AND b.status IN ('ACTIVE','SUPERSEDED')", (rs, row) -> new BindingRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)), actorId, pinnedBindingId, runId);
        if (rows.isEmpty() || rows.get(0).artifactKey() == null || rows.get(0).artifactSha256() == null) throw new BusinessException("CODE_GRAPH_NOT_READY", "Code graph is not ready", HttpStatus.NOT_FOUND);
        var row = rows.get(0);
        var repos = jdbc.query("SELECT repository_alias FROM code_graph_bundle_repository WHERE bundle_id=? ORDER BY repository_alias", (rs, n) -> new GraphRepository(rs.getString(1), rs.getString(1)), row.bundleId());
        return new GraphContext(row.bindingId(), row.version(), row.engineType(), row.bundleId(), new GraphRef(row.artifactKey(), row.artifactSha256(), repos), repos);
    }

    private record BindingRow(long bindingId, int version, String engineType, long bundleId, String artifactKey, String artifactSha256) {}
    private record AgentBinding(long workflowRunId, Long bindingId) {}
    private record GraphContext(long bindingId, int version, String engineType, long bundleId, GraphRef reference, List<GraphRepository> repositories) {}
    public record RepositoryOverview(String alias, String logicalRepositoryKey, String commitSha, String treeSha, String buildMode) {}
    public record Overview(long workflowRunId, long bindingId, int bindingVersion, String engineType,
                           List<RepositoryOverview> repositories, Map<String, Object> graph) {}
    public record QueryResult(long workflowRunId, String operation, Map<String, Object> data) {}
    public record AgentGraphInfo(long workflowRunId, long bindingId, int bindingVersion, String engineType,
                                 List<String> repositories) {}
    public record AgentOverview(long workflowRunId, long bindingId, int bindingVersion, String engineType,
                                Map<String, Object> graph) {}
}

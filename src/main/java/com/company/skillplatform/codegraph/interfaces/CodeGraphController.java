package com.company.skillplatform.codegraph.interfaces;

import com.company.skillplatform.codegraph.application.CodeGraphStatusService;
import com.company.skillplatform.codegraph.application.CodeGraphQueryService;
import com.company.skillplatform.codegraph.application.WorkflowCodeGraphAppendService;
import com.company.skillplatform.codegraph.application.WorkflowCodeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CodeGraphController {
    private final CodeGraphStatusService status;
    private final WorkflowCodeGraphService workflow;
    private final CodeGraphQueryService queries;
    private final WorkflowCodeGraphAppendService updates;
    public CodeGraphController(CodeGraphStatusService status, WorkflowCodeGraphService workflow, CodeGraphQueryService queries,
                               WorkflowCodeGraphAppendService updates) {
        this.status = status; this.workflow = workflow; this.queries = queries; this.updates = updates;
    }

    @PostMapping("/projects/{projectKey}/workflow-runs:prepare")
    public WorkflowCodeGraphService.PrepareResult prepare(@PathVariable String projectKey,
                                                          @RequestBody PrepareRequest request,
                                                          Authentication authentication) {
        return workflow.prepare(projectKey, actor(authentication), request.initialRequest(),
                request.contextSnapshotJson() == null ? null : request.contextSnapshotJson().toString());
    }

    @GetMapping("/workflow-runs/{runId}/code-graph")
    public CodeGraphStatusService.View status(@PathVariable long runId, Authentication authentication) {
        return status.get(runId, actor(authentication));
    }

    @PostMapping("/workflow-runs/{runId}/code-graph:retry")
    public CodeGraphStatusService.View retry(@PathVariable long runId, Authentication authentication) {
        return workflow.retry(runId, actor(authentication));
    }

    /**
     * M7: retry a FAILED REPO_APPEND update. Only the failed target version is rebuilt;
     * the current ACTIVE binding keeps serving traffic throughout.
     */
    @PostMapping("/workflow-runs/{runId}/code-graph/updates/{updateRequestId}:retry")
    public Map<String, Object> retryUpdate(@PathVariable long runId, @PathVariable long updateRequestId,
                                           Authentication authentication) {
        var id = updates.retryUpdate(runId, updateRequestId, actor(authentication));
        return Map.of("updateRequestId", id, "workflowRunId", runId);
    }

    @GetMapping("/workflow-runs/{runId}/code-graph/overview")
    public CodeGraphQueryService.Overview overview(@PathVariable long runId, Authentication authentication) {
        return queries.overview(runId, actor(authentication));
    }

    @PostMapping("/workflow-runs/{runId}/code-graph/search")
    public CodeGraphQueryService.QueryResult search(@PathVariable long runId, @RequestBody QueryRequest request, Authentication authentication) {
        var parameters = new java.util.LinkedHashMap<String, Object>();
        parameters.put("query", request.query());
        if (request.limit() != null) parameters.put("limit", request.limit());
        return queries.execute(runId, actor(authentication), "search", request.repository(), parameters);
    }

    @PostMapping("/workflow-runs/{runId}/code-graph/query")
    public CodeGraphQueryService.QueryResult query(@PathVariable long runId, @RequestBody Map<String, Object> request, Authentication authentication) {
        return execute(runId, "query", request, authentication);
    }

    @PostMapping("/workflow-runs/{runId}/code-graph/node")
    public CodeGraphQueryService.QueryResult node(@PathVariable long runId, @RequestBody Map<String, Object> request, Authentication authentication) {
        return execute(runId, "node", request, authentication);
    }

    @PostMapping("/workflow-runs/{runId}/code-graph/context")
    public CodeGraphQueryService.QueryResult context(@PathVariable long runId, @RequestBody Map<String, Object> request, Authentication authentication) {
        return execute(runId, "context", request, authentication);
    }

    @PostMapping("/workflow-runs/{runId}/code-graph/impact")
    public CodeGraphQueryService.QueryResult impact(@PathVariable long runId, @RequestBody Map<String, Object> request, Authentication authentication) {
        return execute(runId, "impact", request, authentication);
    }

    @PostMapping("/workflow-runs/{runId}/code-graph/trace")
    public CodeGraphQueryService.QueryResult trace(@PathVariable long runId, @RequestBody Map<String, Object> request, Authentication authentication) {
        return execute(runId, "trace", request, authentication);
    }

    @PostMapping("/workflow-runs/{runId}/code-graph/route-map")
    public CodeGraphQueryService.QueryResult routeMap(@PathVariable long runId, @RequestBody Map<String, Object> request, Authentication authentication) {
        return execute(runId, "route-map", request, authentication);
    }

    private CodeGraphQueryService.QueryResult execute(long runId, String operation, Map<String, Object> request, Authentication authentication) {
        var copy = new java.util.LinkedHashMap<>(request == null ? Map.of() : request);
        var repository = copy.remove("repository");
        return queries.execute(runId, actor(authentication), operation, repository == null ? null : String.valueOf(repository), copy);
    }

    @PostMapping("/workflow-runs/{runId}:activate")
    public com.company.skillplatform.agentworkflow.application.AgentWorkflowService.RunView activate(
            @PathVariable long runId, Authentication authentication) {
        return workflow.activate(runId, actor(authentication));
    }

    private long actor(Authentication authentication) { return (Long) authentication.getPrincipal(); }
    public record PrepareRequest(String initialRequest, JsonNode contextSnapshotJson) {}
    public record QueryRequest(String query, String repository, Integer limit) {}
}

package com.company.skillplatform.agentworkflow.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RuntimeCommandDispatcher {
    private final JdbcTemplate jdbc;
    private final RestClient client;
    private final String token;

    public RuntimeCommandDispatcher(JdbcTemplate jdbc, RestClient.Builder builder,
            @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:8090}") String url,
            @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String token) {
        this.jdbc = jdbc;
        this.client = builder.baseUrl(url).build();
        this.token = token;
    }

    @Scheduled(fixedDelayString = "${skill-platform.agent-runtime.dispatch-delay-ms:1000}")
    public void dispatch() {
        List<Long> ids = jdbc.query("select id from runtime_command where status='PENDING' and "
                        + "(next_retry_at is null or next_retry_at<=now(3)) order by id limit 20",
                (row, ignored) -> row.getLong(1));
        for (Long id : ids) {
            if (jdbc.update("update runtime_command set status='SENDING',time_updated=now(3) "
                    + "where id=? and status='PENDING'", id) == 0) continue;
            try {
                send(id);
                jdbc.update("update runtime_command set status='ACKED',time_updated=now(3) where id=?", id);
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                jdbc.update("update runtime_command set status=case when retry_count+1>=5 then 'FAILED' else 'PENDING' end,"
                                + "retry_count=retry_count+1,next_retry_at=date_add(now(3),interval least(power(2,retry_count),60) second),"
                                + "last_error=?,time_updated=now(3) where id=?",
                        message.substring(0, Math.min(message.length(), 2000)), id);
            }
        }
    }

    private void send(Long commandRowId) {
        Map<String, Object> context = jdbc.queryForMap("select c.command_id,ar.id agent_run_id,ar.node_key,"
                + "ar.profile_version_id,ar.session_id,s.id stage_run_id,w.id workflow_run_id,w.project_id,"
                + "v.system_prompt,v.model_code,v.max_iteration_per_run "
                + "from runtime_command c join agent_workflow_run ar on ar.id=c.aggregate_id "
                + "join stage_run s on s.id=ar.stage_run_id join workflow_run w on w.id=s.workflow_run_id "
                + "join agent_profile_version v on v.id=ar.profile_version_id where c.id=?", commandRowId);
        String commandId = String.valueOf(context.get("command_id"));
        long stageRunId = number(context, "stage_run_id");
        long projectId = number(context, "project_id");
        long sessionId = number(context, "session_id");
        long agentRunId = number(context, "agent_run_id");
        Map<String, Object> workspace = ensureRuntime(commandId, projectId, stageRunId);
        String conversationId = ensureConversation(commandId, sessionId, workspace, context);
        Map<?, ?> run = client.post().uri("/internal/v1/conversations/{id}/runs", conversationId)
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":run", "agentRunId", agentRunId,
                        "message", instruction(context), "contextManifest",
                        Map.of("projectId", projectId, "workflowRunId", number(context, "workflow_run_id"),
                                "stageRunId", stageRunId)))
                .retrieve().body(Map.class);
        jdbc.update("update agent_workflow_run set runtime_run_id=?,status='RUNNING',"
                        + "started_at=coalesce(started_at,now(3)),time_updated=now(3) where id=?",
                run == null ? null : run.get("runId"), agentRunId);
    }

    private Map<String, Object> ensureRuntime(String commandId, long projectId, long stageRunId) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select runtime_id,workspace_id from runtime_workspace_binding where stage_run_id=?", stageRunId);
        if (!existing.isEmpty()) return existing.get(0);
        Map<?, ?> created = client.post().uri("/internal/v1/runtimes")
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":runtime", "projectId", projectId,
                        "stageRunId", stageRunId, "source", Map.of("type", "EMPTY"), "resource", Map.of()))
                .retrieve().body(Map.class);
        String runtimeId = String.valueOf(created == null ? null : created.get("runtimeId"));
        jdbc.update("insert into runtime_workspace_binding(time_created,time_updated,stage_run_id,status,runtime_id) "
                + "values(now(3),now(3),?,'ACTIVE',?)", stageRunId, runtimeId);
        return jdbc.queryForMap("select runtime_id,workspace_id from runtime_workspace_binding where stage_run_id=?",
                stageRunId);
    }

    private String ensureConversation(String commandId, long sessionId, Map<String, Object> workspace,
            Map<String, Object> context) {
        String conversationId = jdbc.queryForObject(
                "select runtime_conversation_id from agent_workflow_session where id=?", String.class, sessionId);
        if (conversationId != null) return conversationId;
        List<Map<String, Object>> tools = jdbc.queryForList("select tool_code toolCode,enabled,"
                + "permission_mode permissionMode,config_json config from agent_profile_version_tool "
                + "where agent_profile_version_id=? order by tool_code", context.get("profile_version_id"));
        List<Map<String, Object>> skills = jdbc.queryForList("select s.skill_key code,"
                + "b.fixed_skill_version_id versionId,b.version_policy versionPolicy,b.required "
                + "from agent_profile_version_skill b join skill s on s.id=b.skill_id "
                + "where b.agent_profile_version_id=? order by b.sort_order", context.get("profile_version_id"));
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", context.get("node_key"));
        agent.put("systemPrompt", context.get("system_prompt"));
        agent.put("model", context.get("model_code"));
        agent.put("maxIterationPerRun", context.get("max_iteration_per_run"));
        agent.put("tools", tools);
        agent.put("skills", skills);
        Map<?, ?> created = client.post().uri("/internal/v1/runtimes/{id}/conversations", workspace.get("runtime_id"))
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":conversation", "agentSessionId", sessionId, "agent", agent))
                .retrieve().body(Map.class);
        conversationId = String.valueOf(created == null ? null : created.get("conversationId"));
        Object workspaceId = created == null ? null : created.get("workspaceId");
        jdbc.update("update agent_workflow_session set runtime_conversation_id=?,time_updated=now(3) where id=?",
                conversationId, sessionId);
        if (workspaceId != null) jdbc.update("update runtime_workspace_binding set workspace_id=?,time_updated=now(3) "
                + "where stage_run_id=?", workspaceId, context.get("stage_run_id"));
        return conversationId;
    }

    private String instruction(Map<String, Object> context) {
        return "Execute workflow node '" + context.get("node_key") + "'. Return only the standard JSON envelope "
                + "with executionStatus, resultCode, summary, artifacts, issues, blockingIssues and metrics.";
    }

    private long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
}

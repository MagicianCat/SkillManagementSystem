package com.company.skillplatform.agentworkflow.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.company.skillplatform.agentworkflow.application.ResolvedSkillService;
import com.company.skillplatform.agent.application.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

@Component
public class RuntimeCommandDispatcher {
    private final JdbcTemplate jdbc;
    private final RestClient client;
    private final String token;
    private final String mcpUrl;
    private final ResolvedSkillService skills;
    private final AgentRunService agentRuns;
    private final ObjectMapper json;

    public RuntimeCommandDispatcher(JdbcTemplate jdbc, RestClient.Builder builder,
            @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:8090}") String url,
            @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String token,
            @Value("${skill-platform.agent-runtime.mcp-url:http://host.docker.internal:8090/internal/mcp}") String mcpUrl,
            ResolvedSkillService skills, AgentRunService agentRuns, ObjectMapper json) {
        this.jdbc = jdbc;
        this.client = builder.baseUrl(url).build();
        this.token = token;
        this.mcpUrl = mcpUrl;
        this.skills = skills;
        this.agentRuns = agentRuns;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${skill-platform.agent-runtime.dispatch-delay-ms:1000}")
    public void dispatch() {
        List<Long> ids = jdbc.query("select id from runtime_command where (status='PENDING' and (next_retry_at is null or next_retry_at<=now(3))) or (status='SENDING' and lease_until<now(3)) order by id limit 20",
                (row, ignored) -> row.getLong(1));
        for (Long id : ids) {
            String claim=UUID.randomUUID().toString();
            if (jdbc.update("update runtime_command set status='SENDING',dispatch_claim=?,lease_until=date_add(now(3),interval 30 second),time_updated=now(3) where id=? and ((status='PENDING' and (next_retry_at is null or next_retry_at<=now(3))) or (status='SENDING' and lease_until<now(3)))", claim,id) == 0) continue;
            try {
                String type=jdbc.queryForObject("select command_type from runtime_command where id=?",String.class,id);
                if ("START_AGENT".equals(type)) send(id); else sendIntervention(id,type);
                jdbc.update("update runtime_command set status='ACKED',lease_until=null,time_updated=now(3) where id=? and dispatch_claim=?", id,claim);
                jdbc.update("update human_intervention set status='DISPATCHED',time_updated=now(3) where runtime_command_id=?",id);
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                jdbc.update("update runtime_command set status=case when retry_count+1>=5 then 'FAILED' else 'PENDING' end,"
                                + "retry_count=retry_count+1,next_retry_at=date_add(now(3),interval least(power(2,retry_count),60) second),"
                                + "last_error=?,time_updated=now(3) where id=?",
                        message.substring(0, Math.min(message.length(), 2000)), id);
            }
        }
    }

    private void sendIntervention(Long id, String type) {
        Map<String, Object> command = jdbc.queryForMap(
                "select command_id,aggregate_id,payload_json from runtime_command where id=?", id);
        Map<String, Object> run = jdbc.queryForMap("select ar.id,ar.session_id,s.runtime_conversation_id "
                        + "from agent_workflow_run ar join agent_workflow_session s on s.id=ar.session_id where ar.id=?",
                command.get("aggregate_id"));
        String conversation = String.valueOf(run.get("runtime_conversation_id"));
        if ("null".equals(conversation)) throw new IllegalStateException("Agent conversation is not ready");
        String path = switch (type) {
            case "ASK_AGENT" -> "/internal/v1/conversations/{id}/ask";
            case "SEND_MESSAGE" -> "/internal/v1/conversations/{id}/messages";
            case "PAUSE_AGENT" -> "/internal/v1/conversations/{id}/pause";
            case "RESUME_AGENT" -> "/internal/v1/conversations/{id}/resume";
            case "CANCEL_AGENT" -> "/internal/v1/conversations/{id}/cancel";
            default -> throw new IllegalArgumentException("Unsupported command " + type);
        };
        Map<?, ?> payload = readJson(String.valueOf(command.get("payload_json")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", command.get("command_id"));
        if (Set.of("ASK_AGENT", "SEND_MESSAGE").contains(type)) {
            Object content = payload.get("content");
            body.put("message", content == null ? "" : String.valueOf(content));
        }
        client.post().uri(path, conversation)
                .header("X-SMS-Service-Token", token)
                .header("X-Request-Id", String.valueOf(command.get("command_id")))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    private void send(Long commandRowId) {
        Map<String, Object> context = jdbc.queryForMap("select c.command_id,ar.id agent_run_id,ar.node_key,"
                + "ar.profile_version_id,ar.session_id,s.id stage_run_id,w.id workflow_run_id,w.project_id,p.project_key,w.started_by,w.initial_request,w.context_snapshot_json,"
                + "v.system_prompt,v.model_code,v.temperature,v.max_iteration_per_run,v.timeout_seconds,v.output_schema_json,v.runtime_config_json,ap.code profile_code "
                + "from runtime_command c join agent_workflow_run ar on ar.id=c.aggregate_id "
                + "join stage_run s on s.id=ar.stage_run_id join workflow_run w on w.id=s.workflow_run_id "
                + "join virtual_project p on p.id=w.project_id join agent_profile_version v on v.id=ar.profile_version_id join agent_profile ap on ap.id=v.agent_profile_id where c.id=?", commandRowId);
        String commandId = String.valueOf(context.get("command_id"));
        long stageRunId = number(context, "stage_run_id");
        long projectId = number(context, "project_id");
        long sessionId = number(context, "session_id");
        long agentRunId = number(context, "agent_run_id");
        Map<String, Object> workspace = ensureRuntime(commandId, projectId, stageRunId);
        String conversationId = ensureConversation(commandId, sessionId, workspace, context);
        String runMcpToken = agentRuns.issueWorkflowRun(number(context,"started_by"), String.valueOf(context.get("project_key")),
                String.valueOf(context.get("profile_code")), number(context,"workflow_run_id"), stageRunId, agentRunId).token();
        Map<?, ?> run = client.post().uri("/internal/v1/conversations/{id}/runs", conversationId)
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":run", "agentRunId", agentRunId,
                        "message", instruction(context), "contextManifest",
                        Map.of("projectId", projectId, "workflowRunId", number(context, "workflow_run_id"),
                        "stageRunId", stageRunId), "mcpToken", runMcpToken))
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
                .body(Map.of("requestId", commandId + ":runtime", "projectId", String.valueOf(projectId),
                        "stageRunId", String.valueOf(stageRunId), "source", Map.of("type", "EMPTY"), "resource", Map.of()))
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
        List<Map<String, Object>> resolvedSkills = skills.resolve(number(context,"profile_version_id"),number(context,"workflow_run_id"));
        String mcpToken=agentRuns.issueWorkflowRun(number(context,"started_by"),String.valueOf(context.get("project_key")),String.valueOf(context.get("profile_code")),number(context,"workflow_run_id"),number(context,"stage_run_id"),number(context,"agent_run_id")).token();
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", context.get("node_key"));
        agent.put("systemPrompt", context.get("system_prompt"));
        agent.put("platformBaseInstructions", "Respect SMS project authorization and MCP boundaries. Never access context, tools, tokens, or secrets outside the current workflow run.");
        agent.put("model", context.get("model_code"));
        agent.put("temperature", context.get("temperature"));
        agent.put("maxIterationPerRun", context.get("max_iteration_per_run"));
        agent.put("timeoutSeconds", context.get("timeout_seconds"));
        agent.put("outputSchema", readJson(String.valueOf(context.get("output_schema_json"))));
        agent.put("runtimeConfig", readJson(String.valueOf(context.get("runtime_config_json"))));
        agent.put("tools", tools);
        agent.put("skills", resolvedSkills);
        agent.put("mcp",Map.of("url",mcpUrl,"smsToken",mcpToken));
        Map<?, ?> created = client.post().uri("/internal/v1/runtimes/{id}/conversations", workspace.get("runtime_id"))
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":conversation", "agentSessionId", String.valueOf(sessionId), "agent", agent))
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
        StringBuilder instruction=new StringBuilder("Execute Requirement workflow node '").append(context.get("node_key")).append("'. Initial request:\n").append(context.get("initial_request")).append("\nReturn only the standard JSON envelope with schemaVersion, executionStatus, resultCode, summary, artifacts, reviewedArtifactRevisionIds, issues, blockingIssues and metrics.");
        if("writer".equals(context.get("node_key"))){List<Map<String,Object>> review=jdbc.queryForList("select result_json from agent_workflow_run where stage_run_id=? and node_key='reviewer' and result_code='REVISION_REQUIRED' order by id desc limit 1",context.get("stage_run_id"));if(!review.isEmpty()){instruction.append("\nAddress this reviewer result: ").append(review.get(0).get("result_json"));List<Map<String,Object>> artifact=jdbc.queryForList("select b.project_document_id artifactId,d.version_no versionNo,b.project_document_revision_id revisionId from workflow_artifact_binding b join project_document d on d.id=b.project_document_id join project_document_revision r on r.id=b.project_document_revision_id where b.stage_run_id=? and b.artifact_kind='REQUIREMENT' and b.relation_type='OUTPUT' order by r.revision_no desc limit 1",context.get("stage_run_id"));instruction.append("\nUpdate exactly this existing artifact using artifactId and versionNo: ").append(writeJson(artifact.isEmpty()?Map.of():artifact.get(0)));}}
        if("reviewer".equals(context.get("node_key"))){List<Map<String,Object>> artifact=jdbc.queryForList("select b.project_document_id,b.project_document_revision_id,r.revision_no from workflow_artifact_binding b join project_document_revision r on r.id=b.project_document_revision_id where b.stage_run_id=? and b.artifact_kind='REQUIREMENT' and b.relation_type='OUTPUT' order by r.revision_no desc limit 1",context.get("stage_run_id"));instruction.append("\nReview exactly this latest artifact: ").append(writeJson(artifact.isEmpty()?Map.of():artifact.get(0)));}
        return instruction.toString();
    }

    private long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
    @SuppressWarnings("unchecked") private Map<?,?> readJson(String value){try{return json.readValue(value,Map.class);}catch(Exception failure){return Map.of();}}
    private String writeJson(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){return "{}";}}
}

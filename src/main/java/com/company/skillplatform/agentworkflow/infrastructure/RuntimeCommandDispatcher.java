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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import com.company.skillplatform.agentworkflow.application.ResolvedSkillService;
import com.company.skillplatform.agentworkflow.application.WorkflowRuntimeEventService;
import com.company.skillplatform.agentworkflow.application.WorkflowSseService;
import com.company.skillplatform.agent.application.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

@Component
public class RuntimeCommandDispatcher {
    private static final String HUMAN_RESUME_PROTOCOL = "After a human answer is received, resume the interrupted workflow immediately. "
            + "Treat the answer as authoritative context for the current task. Do not only acknowledge the answer, "
            + "do not say that you are still waiting, and do not call workflow_request_human_input again unless a new "
            + "unresolved blocking question is discovered. Continue the analysis and, when complete, call finish with "
            + "the exact structured completion envelope required by the workflow protocol.";
    private final JdbcTemplate jdbc;
    private final RestClient client;
    private final String token;
    private final String mcpUrl;
    private final ResolvedSkillService skills;
    private final AgentRunService agentRuns;
    private final ObjectMapper json;
    private final WorkflowRuntimeEventService runtimeEvents;
    private final WorkflowSseService sse;

    public RuntimeCommandDispatcher(JdbcTemplate jdbc, RestClient.Builder builder,
            @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:8090}") String url,
            @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String token,
            @Value("${skill-platform.agent-runtime.mcp-url:http://host.docker.internal:8090/internal/mcp}") String mcpUrl,
            ResolvedSkillService skills, AgentRunService agentRuns, ObjectMapper json,
            WorkflowRuntimeEventService runtimeEvents, WorkflowSseService sse) {
        this.jdbc = jdbc;
        this.client = builder.baseUrl(url).build();
        this.token = token;
        this.mcpUrl = mcpUrl;
        this.skills = skills;
        this.agentRuns = agentRuns;
        this.json = json;
        this.runtimeEvents = runtimeEvents;
        this.sse = sse;
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
                if("ANSWER_HUMAN_QUESTION".equals(type)) completeHumanAnswer(id);
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                jdbc.update("update runtime_command set status=case when retry_count+1>=5 then 'FAILED' else 'PENDING' end,"
                                + "retry_count=retry_count+1,next_retry_at=date_add(now(3),interval least(power(2,retry_count),60) second),"
                                + "last_error=?,time_updated=now(3) where id=?",
                        message.substring(0, Math.min(message.length(), 2000)), id);
                if ("START_AGENT".equals(jdbc.queryForObject("select command_type from runtime_command where id=?", String.class, id))
                        && "FAILED".equals(jdbc.queryForObject("select status from runtime_command where id=?", String.class, id))) failAgentStart(id, message);
                failHumanAnswerIfExhausted(id, message);
            }
        }
    }

    private void sendIntervention(Long id, String type) {
        Map<String, Object> command = jdbc.queryForMap(
                "select command_id,aggregate_id,payload_json from runtime_command where id=?", id);
        Map<String, Object> run = jdbc.queryForMap("select ar.id,ar.session_id,ar.runtime_conversation_id "
                        + "from agent_workflow_run ar join agent_workflow_session s on s.id=ar.session_id where ar.id=?",
                command.get("aggregate_id"));
        String conversation = String.valueOf(run.get("runtime_conversation_id"));
        if ("null".equals(conversation)) throw new IllegalStateException("Agent conversation is not ready");
        String path = switch (type) {
            case "ASK_AGENT" -> "/internal/v1/conversations/{id}/ask";
            case "SEND_MESSAGE" -> "/internal/v1/conversations/{id}/messages";
            case "ANSWER_HUMAN_QUESTION" -> "/internal/v1/conversations/{id}/messages";
            case "PAUSE_AGENT" -> "/internal/v1/conversations/{id}/pause";
            case "RESUME_AGENT" -> "/internal/v1/conversations/{id}/resume";
            case "CANCEL_AGENT" -> "/internal/v1/conversations/{id}/cancel";
            default -> throw new IllegalArgumentException("Unsupported command " + type);
        };
        Map<?, ?> payload = readJson(String.valueOf(command.get("payload_json")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", command.get("command_id"));
        if (Set.of("ASK_AGENT", "SEND_MESSAGE", "ANSWER_HUMAN_QUESTION").contains(type)) {
            Object content = payload.get("content");
            String message = content == null ? "" : String.valueOf(content);
            if ("ANSWER_HUMAN_QUESTION".equals(type)) {
                message = "Human answer to the workflow question:\n" + message
                        + "\n\nWorkflow resume instruction: " + HUMAN_RESUME_PROTOCOL;
            }
            body.put("message", message);
        }
        client.post().uri(path, conversation)
                .header("X-SMS-Service-Token", token)
                .header("X-Request-Id", String.valueOf(command.get("command_id")))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    private void completeHumanAnswer(Long commandId) {
        List<Map<String,Object>> rows=jdbc.queryForList("select id,workflow_run_id,stage_run_id,agent_session_id,agent_run_id from workflow_human_question where runtime_command_id=? and status='ANSWER_SUBMITTED'",commandId);
        if(rows.isEmpty())return;Map<String,Object> q=rows.get(0);
        int resumed=jdbc.update("update agent_workflow_run set status='RUNNING',time_updated=now(3) where id=? and status='WAITING_HUMAN'",q.get("agent_run_id"));
        if(resumed!=1){
            String error="Agent run is no longer waiting for human input";
            jdbc.update("update workflow_human_question set status='CANCELLED',last_error=?,time_updated=now(3) where id=? and status='ANSWER_SUBMITTED'",error,q.get("id"));
            publishHumanQuestion(q,"human.question.answer_failed",error);
            return;
        }
        jdbc.update("update workflow_human_question set status='ANSWERED',answered_at=now(3),time_updated=now(3) where id=?",q.get("id"));
        jdbc.update("update agent_workflow_session set status='ACTIVE',time_updated=now(3) where id=? and status='WAITING_HUMAN'",q.get("agent_session_id"));
        jdbc.update("update stage_run set status='RUNNING',time_updated=now(3) where id=? and status in ('HUMAN_REQUIRED','WAITING_HUMAN')",q.get("stage_run_id"));
        jdbc.update("update workflow_run set status='RUNNING',time_updated=now(3) where id=? and status='WAITING_HUMAN'",q.get("workflow_run_id"));
        publishHumanQuestion(q, "human.question.answered", null);
    }

    private void failHumanAnswerIfExhausted(Long commandId, String message) {
        String commandStatus = jdbc.queryForObject("select status from runtime_command where id=?", String.class, commandId);
        if (!"FAILED".equals(commandStatus)) return;
        List<Map<String,Object>> rows = jdbc.queryForList("select id,workflow_run_id,stage_run_id,agent_session_id,agent_run_id from workflow_human_question where runtime_command_id=? and status='ANSWER_SUBMITTED'", commandId);
        if (rows.isEmpty()) return;
        Map<String,Object> question = rows.get(0);
        String error = message.substring(0, Math.min(message.length(), 2000));
        jdbc.update("update workflow_human_question set status='PENDING',runtime_command_id=null,last_error=?,time_updated=now(3) where id=? and status='ANSWER_SUBMITTED'", error, question.get("id"));
        publishHumanQuestion(question, "human.question.answer_failed", error);
    }

    private void failAgentStart(Long commandId, String message) {
        Map<String,Object> run=jdbc.queryForMap("select ar.id,ar.stage_run_id,ar.session_id,sr.workflow_run_id from runtime_command c join agent_workflow_run ar on ar.id=c.aggregate_id join stage_run sr on sr.id=ar.stage_run_id where c.id=?",commandId);
        String error=message.substring(0,Math.min(message.length(),2000));
        jdbc.update("update agent_workflow_run set status='FAILED',error_code='AGENT_START_DISPATCH_FAILED',error_message=?,completed_at=now(3),time_updated=now(3) where id=? and status in ('QUEUED','STARTING')",error,run.get("id"));
        Integer latest=jdbc.queryForObject("select count(*) from agent_workflow_run x where x.session_id=? and x.id>? and x.status in ('QUEUED','STARTING','RUNNING','WAITING_HUMAN','PAUSED')",Integer.class,run.get("session_id"),run.get("id"));
        if(latest==null||latest==0){jdbc.update("update stage_run set status='HUMAN_REQUIRED',time_updated=now(3) where id=? and status='RUNNING'",run.get("stage_run_id"));jdbc.update("update workflow_run set status='HUMAN_REQUIRED',time_updated=now(3) where id=? and status not in ('COMPLETED','CANCELLED','FAILED')",run.get("workflow_run_id"));Map<String,Object> data=new LinkedHashMap<>();data.put("stageId",run.get("stage_run_id"));data.put("agentRunId",run.get("id"));data.put("errorCode","AGENT_START_DISPATCH_FAILED");data.put("errorMessage",error);var event=runtimeEvents.append("agent-start-failed-"+commandId,number(run,"workflow_run_id"),number(run,"stage_run_id"),number(run,"session_id"),number(run,"id"),"agent.status.changed",data);sse.publish(number(run,"workflow_run_id"),event);}
    }

    private void publishHumanQuestion(Map<String,Object> question, String type, String error) {
        long workflowRunId = number(question, "workflow_run_id");
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("questionId", question.get("id"));
        data.put("stageId", question.get("stage_run_id"));
        data.put("agentSessionId", question.get("agent_session_id"));
        data.put("agentRunId", question.get("agent_run_id"));
        if (error != null) data.put("error", error);
        var event = runtimeEvents.append(type + "-" + question.get("id") + "-" + UUID.randomUUID(), workflowRunId,
                number(question, "stage_run_id"), number(question, "agent_session_id"), number(question, "agent_run_id"), type, data);
        sse.publish(workflowRunId, event);
    }

    private void send(Long commandRowId) {
        Map<String, Object> context = jdbc.queryForMap("select c.command_id,ar.id agent_run_id,ar.node_key,"
                + "ar.profile_version_id,ar.session_id,s.id stage_run_id,s.stage_key,s.input_snapshot_json,w.id workflow_run_id,w.project_id,p.project_key,w.started_by,w.initial_request,w.context_snapshot_json,"
                + "v.system_prompt,v.model_code,v.temperature,v.max_iteration_per_run,v.timeout_seconds,v.output_schema_json,v.runtime_config_json,ap.code profile_code,"
                + "(select nd.workflow_role from stage_agent_node_def nd join workflow_stage_def sd on sd.id=nd.stage_def_id join workflow_template_version tv on tv.id=sd.workflow_version_id join workflow_template wt on wt.id=tv.workflow_template_id where wt.code=w.workflow_code and tv.version_no=w.workflow_version and sd.stage_key=s.stage_key and nd.node_key=ar.node_key limit 1) workflow_role,"
                + "(select sd.artifact_type from workflow_stage_def sd join workflow_template_version tv on tv.id=sd.workflow_version_id join workflow_template wt on wt.id=tv.workflow_template_id where wt.code=w.workflow_code and tv.version_no=w.workflow_version and sd.stage_key=s.stage_key limit 1) artifact_type,"
                + "(select nd.protocol_prompt from stage_agent_node_def nd join workflow_stage_def sd on sd.id=nd.stage_def_id join workflow_template_version tv on tv.id=sd.workflow_version_id join workflow_template wt on wt.id=tv.workflow_template_id where wt.code=w.workflow_code and tv.version_no=w.workflow_version and sd.stage_key=s.stage_key and nd.node_key=ar.node_key limit 1) workflow_protocol_prompt,"
                + "(select nd.output_schema_json from stage_agent_node_def nd join workflow_stage_def sd on sd.id=nd.stage_def_id join workflow_template_version tv on tv.id=sd.workflow_version_id join workflow_template wt on wt.id=tv.workflow_template_id where wt.code=w.workflow_code and tv.version_no=w.workflow_version and sd.stage_key=s.stage_key and nd.node_key=ar.node_key limit 1) workflow_output_schema_json "
                + "from runtime_command c join agent_workflow_run ar on ar.id=c.aggregate_id "
                + "join stage_run s on s.id=ar.stage_run_id join workflow_run w on w.id=s.workflow_run_id "
                + "join virtual_project p on p.id=w.project_id join agent_profile_version v on v.id=ar.profile_version_id join agent_profile ap on ap.id=v.agent_profile_id where c.id=?", commandRowId);
        String commandId = String.valueOf(context.get("command_id"));
        long stageRunId = number(context, "stage_run_id");
        long projectId = number(context, "project_id");
        long sessionId = number(context, "session_id");
        long agentRunId = number(context, "agent_run_id");
        Map<String, Object> workspace = ensureRuntime(commandId, projectId, stageRunId);
        String conversationId;
        try {
            conversationId = ensureConversation(commandId, sessionId, agentRunId, workspace, context);
        } catch (HttpClientErrorException.NotFound staleRuntime) {
            workspace = recreateRuntime(commandId, projectId, stageRunId);
            conversationId = ensureConversation(commandId, sessionId, agentRunId, workspace, context);
        }
        String runMcpToken = agentRuns.issueWorkflowRun(number(context,"started_by"), String.valueOf(context.get("project_key")),
                String.valueOf(context.get("profile_code")), number(context,"workflow_run_id"), stageRunId, agentRunId).token();
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("projectId", projectId);
        manifest.put("workflowRunId", number(context, "workflow_run_id"));
        manifest.put("stageRunId", stageRunId);
        manifest.put("inputArtifacts", readList(String.valueOf(context.get("input_snapshot_json"))));
        Map<?, ?> run = client.post().uri("/internal/v1/conversations/{id}/runs", conversationId)
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":run", "agentRunId", agentRunId,
                        "message", instruction(context), "contextManifest", manifest, "mcpToken", runMcpToken))
                .retrieve().body(Map.class);
        jdbc.update("update agent_workflow_run set runtime_run_id=?,runtime_conversation_id=?,status='RUNNING',"
                        + "started_at=coalesce(started_at,now(3)),time_updated=now(3) where id=?",
                run == null ? null : run.get("runId"), conversationId, agentRunId);
    }

    private Map<String, Object> ensureRuntime(String commandId, long projectId, long stageRunId) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select runtime_id,workspace_id from runtime_workspace_binding where stage_run_id=?", stageRunId);
        if (!existing.isEmpty()) return existing.get(0);
        return recreateRuntime(commandId,projectId,stageRunId);
    }

    private Map<String,Object> recreateRuntime(String commandId,long projectId,long stageRunId) {
        Map<?, ?> created = client.post().uri("/internal/v1/runtimes")
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":runtime:"+UUID.randomUUID(), "projectId", String.valueOf(projectId),
                        "stageRunId", String.valueOf(stageRunId), "source", Map.of("type", "EMPTY"), "resource", Map.of()))
                .retrieve().body(Map.class);
        String runtimeId = String.valueOf(created == null ? null : created.get("runtimeId"));
        jdbc.update("insert into runtime_workspace_binding(time_created,time_updated,stage_run_id,status,runtime_id) "
                + "values(now(3),now(3),?,'ACTIVE',?) on duplicate key update runtime_id=values(runtime_id),workspace_id=null,status='ACTIVE',time_updated=now(3)", stageRunId, runtimeId);
        return jdbc.queryForMap("select runtime_id,workspace_id from runtime_workspace_binding where stage_run_id=?",
                stageRunId);
    }

    private String ensureConversation(String commandId, long sessionId, long agentRunId, Map<String, Object> workspace,
            Map<String, Object> context) {
        List<Map<String, Object>> tools = jdbc.queryForList("select tool_code toolCode,enabled,"
                + "permission_mode permissionMode,config_json config from agent_profile_version_tool "
                + "where agent_profile_version_id=? order by tool_code", context.get("profile_version_id"));
        List<Map<String, Object>> resolvedSkills = skills.resolve(number(context,"profile_version_id"),number(context,"workflow_run_id"));
        String mcpToken=agentRuns.issueWorkflowRun(number(context,"started_by"),String.valueOf(context.get("project_key")),String.valueOf(context.get("profile_code")),number(context,"workflow_run_id"),number(context,"stage_run_id"),number(context,"agent_run_id")).token();
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", context.get("node_key"));
        agent.put("systemPrompt", context.get("system_prompt"));
        agent.put("platformBaseInstructions", "Respect SMS project authorization and MCP boundaries. Never access context, tools, tokens, or secrets outside the current workflow run. When human input is required, call workflow_request_human_input with exactly one question, then stop the current turn and wait for the answer; never simulate waiting with plain text. " + HUMAN_RESUME_PROTOCOL);
        agent.put("model", context.get("model_code"));
        agent.put("temperature", context.get("temperature"));
        agent.put("maxIterationPerRun", context.get("max_iteration_per_run"));
        agent.put("timeoutSeconds", context.get("timeout_seconds"));
        Object workflowSchema = context.get("workflow_output_schema_json");
        agent.put("outputSchema", workflowSchema == null ? readJson(String.valueOf(context.get("output_schema_json"))) : readJson(String.valueOf(workflowSchema)));
        agent.put("runtimeConfig", readJson(String.valueOf(context.get("runtime_config_json"))));
        agent.put("workflowProtocolPrompt", String.valueOf(context.getOrDefault("workflow_protocol_prompt", ""))
                + "\n\n" + HUMAN_RESUME_PROTOCOL);
        agent.put("workflowRole", context.get("workflow_role"));
        agent.put("artifactType", context.get("artifact_type"));
        if ("REVIEWER".equalsIgnoreCase(String.valueOf(context.get("workflow_role")))) {
            List<Long> revisions=jdbc.query("select b.project_document_revision_id from workflow_artifact_binding b join project_document_revision r on r.id=b.project_document_revision_id where b.stage_run_id=? and b.artifact_kind=? and b.relation_type='OUTPUT' order by r.revision_no desc,b.id desc limit 1",(rs,n)->rs.getLong(1),context.get("stage_run_id"),context.get("artifact_type"));
            agent.put("expectedRevisionIds",revisions);
        }
        agent.put("tools", tools);
        agent.put("skills", resolvedSkills);
        agent.put("mcp",Map.of("url",mcpUrl,"smsToken",mcpToken));
        Map<?, ?> created = client.post().uri("/internal/v1/runtimes/{id}/conversations", workspace.get("runtime_id"))
                .header("X-SMS-Service-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requestId", commandId + ":conversation", "agentSessionId", String.valueOf(sessionId), "agent", agent))
                .retrieve().body(Map.class);
        String conversationId = String.valueOf(created == null ? null : created.get("conversationId"));
        Object workspaceId = created == null ? null : created.get("workspaceId");
        jdbc.update("update agent_workflow_run set runtime_conversation_id=?,time_updated=now(3) where id=?",
                conversationId, agentRunId);
        jdbc.update("update agent_workflow_session set runtime_conversation_id=?,time_updated=now(3) where id=?",
                conversationId, sessionId);
        if (workspaceId != null) jdbc.update("update runtime_workspace_binding set workspace_id=?,time_updated=now(3) "
                + "where stage_run_id=?", workspaceId, context.get("stage_run_id"));
        return conversationId;
    }

    private String instruction(Map<String, Object> context) {
        String stage = String.valueOf(context.getOrDefault("stage_key", "design"));
        String role = String.valueOf(context.getOrDefault("workflow_role", ""));
        String artifactKind = String.valueOf(context.getOrDefault("artifact_type", "REQUIREMENT"));
        StringBuilder instruction = new StringBuilder("Execute ").append(stage).append(" workflow node '")
                .append(context.get("node_key")).append("'. Initial request:\n").append(context.get("initial_request"));
        List<?> inputs = readList(String.valueOf(context.get("input_snapshot_json")));
        if (!inputs.isEmpty()) {
            instruction.append("\nRequired upstream artifacts (immutable input revisions): ")
                    .append(writeJson(inputs))
                    .append("\nRead every listed documentId/revisionId with get_project_artifact before doing the task. "
                            + "Use exactly these revisions; do not substitute a newer project document.");
        }
        if ("AUTHOR".equalsIgnoreCase(role) || "writer".equals(context.get("node_key"))) {
            List<Map<String,Object>> review = jdbc.queryForList("select result_json from agent_workflow_run where stage_run_id=? and node_key='reviewer' and result_code='REVISION_REQUIRED' order by id desc limit 1", context.get("stage_run_id"));
            if (!review.isEmpty()) instruction.append("\nAddress this reviewer result: ").append(review.get(0).get("result_json"));
            List<Map<String,Object>> artifact = jdbc.queryForList("select b.project_document_id artifactId,d.version_no versionNo,b.project_document_revision_id revisionId from workflow_artifact_binding b join project_document d on d.id=b.project_document_id join project_document_revision r on r.id=b.project_document_revision_id where b.stage_run_id=? and b.artifact_kind=? and b.relation_type='OUTPUT' order by r.revision_no desc limit 1", context.get("stage_run_id"), artifactKind);
            if (!artifact.isEmpty()) instruction.append("\nUpdate exactly this existing artifact using artifactId and versionNo: ").append(writeJson(artifact.get(0)));
        }
        if ("REVIEWER".equalsIgnoreCase(role) || "reviewer".equals(context.get("node_key"))) {
            List<Map<String,Object>> artifact = jdbc.queryForList("select b.project_document_id,b.project_document_revision_id,r.revision_no from workflow_artifact_binding b join project_document_revision r on r.id=b.project_document_revision_id where b.stage_run_id=? and b.artifact_kind=? and b.relation_type='OUTPUT' order by r.revision_no desc limit 1", context.get("stage_run_id"), artifactKind);
            instruction.append("\nReview exactly this latest artifact: ").append(writeJson(artifact.isEmpty()?Map.of():artifact.get(0)));
        }
        return instruction.toString();
    }

    private long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
    @SuppressWarnings("unchecked") private Map<?,?> readJson(String value){try{Map<?,?> result=json.readValue(value,Map.class);return result==null?Map.of():result;}catch(Exception failure){return Map.of();}}
    @SuppressWarnings("unchecked") private List<?> readList(String value){try{List<?> result=json.readValue(value,List.class);return result==null?List.of():result;}catch(Exception failure){return List.of();}}
    private String writeJson(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){return "{}";}}
}

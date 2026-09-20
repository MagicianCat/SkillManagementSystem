package com.company.skillplatform.agentworkflow.infrastructure;

import com.company.skillplatform.agentworkflow.application.AgentWorkflowService;
import com.company.skillplatform.agentworkflow.application.WorkflowRuntimeEventService;
import com.company.skillplatform.agentworkflow.application.WorkflowSseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RuntimeEventCollector {
    private static final Logger log = LoggerFactory.getLogger(RuntimeEventCollector.class);
    private final JdbcTemplate jdbc;
    private final AgentWorkflowService workflows;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final String token;
    private final WorkflowRuntimeEventService eventStore;
    private final WorkflowSseService sse;

    public RuntimeEventCollector(JdbcTemplate jdbc, AgentWorkflowService workflows, ObjectMapper objectMapper,
            RestClient.Builder builder, WorkflowRuntimeEventService eventStore, WorkflowSseService sse,
            @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:8090}") String url,
            @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String token) {
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.objectMapper = objectMapper;
        this.client = builder.baseUrl(url).build();
        this.token = token;
        this.eventStore = eventStore;
        this.sse = sse;
    }

    @Scheduled(fixedDelayString = "${skill-platform.agent-runtime.event-delay-ms:1000}")
    public void collect() {
        List<Map<String, Object>> active = jdbc.queryForList("select ar.id agent_run_id,ar.node_key,ar.session_id,ar.stage_run_id,"
                + "ar.runtime_event_sequence,ar.runtime_conversation_id,sr.workflow_run_id "
                + "from agent_workflow_run ar join agent_workflow_session s on s.id=ar.session_id "
                + "join stage_run sr on sr.id=ar.stage_run_id where ar.status in ('STARTING','RUNNING') "
                + "and ar.runtime_run_id is not null and ar.runtime_conversation_id is not null");
        for (Map<String, Object> run : active) {
            try {
                collectRun(run);
            } catch (RuntimeException failure) {
                log.warn("event=agent.workflow.runtime.collect_failed agentRunId={} error={}",
                        run.get("agent_run_id"), failure.getMessage());
            }
        }
    }

    private void collectRun(Map<String, Object> run) {
        long cursor = ((Number) run.get("runtime_event_sequence")).longValue();
        long after = cursor;
        List<?> events = client.get().uri(uri -> uri.path("/internal/v1/conversations/{id}/events")
                        .queryParam("after", after).build(run.get("runtime_conversation_id")))
                .header("X-SMS-Service-Token", token).retrieve().body(List.class);
        if (events == null) return;
        for (Object item : events) {
            if (!(item instanceof Map<?, ?> event)
                    || !String.valueOf(run.get("agent_run_id")).equals(String.valueOf(event.get("agentRunId")))) continue;
            Object sequenceValue = event.get("sequence");
            long sequence = sequenceValue instanceof Number number ? number.longValue() : 0L;
            if (sequence <= cursor) continue;
            String type = String.valueOf(event.get("type"));
            Map<?, ?> payload = event.get("payload") instanceof Map<?, ?> value ? value : Map.of();
            Map<String,Object> eventData = new java.util.LinkedHashMap<>();
            payload.forEach((key,value) -> eventData.put(String.valueOf(key), value));
            eventData.put("stageId", run.get("stage_run_id"));
            eventData.put("agentSessionId", run.get("session_id"));
            eventData.put("agentRunId", run.get("agent_run_id"));
            eventData.put("agentNodeKey", run.get("node_key"));
            String storedType = frontendType(type);
            WorkflowRuntimeEventService.StoredEvent stored = eventStore.append(String.valueOf(event.get("eventId")),
                    ((Number) run.get("workflow_run_id")).longValue(), ((Number) run.get("stage_run_id")).longValue(),
                    ((Number) run.get("session_id")).longValue(), ((Number) run.get("agent_run_id")).longValue(), storedType, eventData);
            sse.publish(((Number) run.get("workflow_run_id")).longValue(), stored);
            if ("AGENT_RUN_COMPLETED".equals(type) || "AGENT_RUN_FAILED".equals(type)) {
                String executionStatus = "AGENT_RUN_COMPLETED".equals(type)
                        ? text(payload, "executionStatus", "SUCCESS") : "FAILED";
                String resultCode = text(payload, "resultCode",
                        "AGENT_RUN_COMPLETED".equals(type) ? "COMPLETED" : "RUNTIME_FAILED");
                workflows.ingestEventInternal(((Number) run.get("workflow_run_id")).longValue(),
                        String.valueOf(event.get("eventId")), String.valueOf(run.get("node_key")), executionStatus,
                        resultCode, json(payload));
            }
            cursor = sequence;
            jdbc.update("update agent_workflow_run set runtime_event_sequence=?,time_updated=now(3) where id=?",
                    cursor, run.get("agent_run_id"));
        }
    }

    private String frontendType(String type) {
        return switch (type) {
            case "AGENT_MESSAGE_DELTA" -> "agent.message.delta";
            case "AGENT_MESSAGE_COMPLETED" -> "agent.message.completed";
            case "TOOL_STARTED" -> "tool.started";
            case "TOOL_COMPLETED" -> "tool.completed";
            case "TOOL_FAILED" -> "tool.failed";
            case "ARTIFACT_REVISION_CREATED", "ARTIFACT_CANDIDATE" -> "artifact.revision.created";
            case "AGENT_PAUSED" -> "agent.paused";
            case "AGENT_RESUMED" -> "agent.resumed";
            case "AGENT_PROTOCOL_RETRY_REQUESTED" -> "agent.protocol.retry.requested";
            default -> "agent.status.changed";
        };
    }

    private String json(Map<?, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize runtime result", failure);
        }
    }

    private String text(Map<?, ?> value, String key, String fallback) {
        Object candidate = value.get(key);
        return candidate == null ? fallback : String.valueOf(candidate);
    }
}

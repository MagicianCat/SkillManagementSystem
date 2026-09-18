package com.company.skillplatform.agentworkflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowRuntimeEventService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkflowRuntimeEventService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public StoredEvent append(String eventId, long workflowRunId, Long stageRunId, Long sessionId,
                              Long agentRunId, String type, Object payload) {
        List<StoredEvent> existing = byEventId(eventId);
        if (!existing.isEmpty()) return existing.get(0);
        Long sequence = jdbc.queryForObject("select coalesce(max(event_seq),0)+1 from workflow_runtime_event where workflow_run_id=?", Long.class, workflowRunId);
        jdbc.update("insert ignore into workflow_runtime_event(event_id,workflow_run_id,stage_run_id,agent_session_id,agent_run_id,event_seq,event_type,payload_json,time_created) values(?,?,?,?,?,?,?,?,now(3))",
                eventId, workflowRunId, stageRunId, sessionId, agentRunId, sequence, type, write(payload));
        return byEventId(eventId).get(0);
    }

    public List<StoredEvent> after(long workflowRunId, long sequence) {
        return jdbc.query("select event_id,event_seq,event_type,payload_json,time_created from workflow_runtime_event where workflow_run_id=? and event_seq>? order by event_seq",
                (rs, ignored) -> new StoredEvent(rs.getString(1), rs.getLong(2), rs.getString(3), read(rs.getString(4)), rs.getTimestamp(5).toInstant()), workflowRunId, sequence);
    }

    private List<StoredEvent> byEventId(String eventId) {
        return jdbc.query("select event_id,event_seq,event_type,payload_json,time_created from workflow_runtime_event where event_id=?",
                (rs, ignored) -> new StoredEvent(rs.getString(1), rs.getLong(2), rs.getString(3), read(rs.getString(4)), rs.getTimestamp(5).toInstant()), eventId);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Runtime event payload is not JSON", failure); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String value) {
        try { return json.readValue(value, Map.class); }
        catch (JsonProcessingException failure) { return Map.of("raw", value); }
    }

    public record StoredEvent(String eventId, long sequence, String type, Map<String, Object> data, Instant createdAt) {}
}

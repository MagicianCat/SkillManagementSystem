package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.agentworkflow.application.WorkflowRuntimeEventService;
import com.company.skillplatform.agentworkflow.application.WorkflowSseService;
import com.company.skillplatform.notification.domain.NotificationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeGraphLifecyclePublisher {
    private final WorkflowRuntimeEventService events;
    private final WorkflowSseService sse;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CodeGraphLifecyclePublisher(WorkflowRuntimeEventService events, WorkflowSseService sse,
                                       JdbcTemplate jdbc, ObjectMapper mapper) {
        this.events = events; this.sse = sse; this.jdbc = jdbc; this.mapper = mapper;
    }

    public void event(long workflowRunId, String type, Map<String, Object> payload) {
        var stored = events.append("code-graph-" + UUID.randomUUID(), workflowRunId, null, null, null, type, payload);
        sse.publish(workflowRunId, stored);
    }

    public void terminal(long workflowRunId, long jobId, boolean succeeded, String detail) {
        var type = succeeded ? NotificationType.CODE_GRAPH_READY : NotificationType.CODE_GRAPH_FAILED;
        var recipients = new LinkedHashSet<>(jdbc.query("SELECT DISTINCT recipient FROM (SELECT p.created_by recipient FROM workflow_run w JOIN virtual_project p ON p.id=w.project_id WHERE w.id=? UNION SELECT m.user_id recipient FROM workflow_run w JOIN virtual_project_member m ON m.project_id=w.project_id AND m.status='ACTIVE' AND m.membership_type='OWNER' WHERE w.id=?) x",
                (rs, row) -> rs.getLong(1), workflowRunId, workflowRunId));
        var title = succeeded ? "代码图谱已准备完成" : "代码图谱准备失败";
        var content = succeeded ? "项目代码图谱已就绪，可继续启动研发工作流" : "项目代码图谱准备失败：" + safe(detail);
        var targetData = json(Map.of("workflowRunId", workflowRunId, "jobId", jobId));
        recipients.forEach(recipient -> jdbc.update("INSERT INTO user_notification(time_created,time_updated,recipient_id,notification_type,title,content,target_type,target_id,target_data) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,CAST(? AS JSON))",
                recipient, type.name(), title, content, "CODE_GRAPH", jobId, targetData));
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.substring(0, Math.min(value.length(), 1500));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Code graph notification data is invalid", exception); }
    }
}

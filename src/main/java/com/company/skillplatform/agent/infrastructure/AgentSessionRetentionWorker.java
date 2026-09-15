package com.company.skillplatform.agent.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Removes the child records of user-deleted Agent sessions after the retention window. */
@Component
public class AgentSessionRetentionWorker {
    private final JdbcTemplate jdbc;
    private final Duration retention;

    public AgentSessionRetentionWorker(JdbcTemplate jdbc,
            @Value("${skill-platform.agent.session-retention:PT4320H}") Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${skill-platform.agent.session-retention-scan-ms:3600000}")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(retention);
        List<Map<String, Object>> sessions = jdbc.queryForList(
                "SELECT id, session_key FROM agent_session WHERE status='DELETED' AND deleted_at < ? ORDER BY deleted_at LIMIT 100",
                Timestamp.from(cutoff));
        for (Map<String, Object> row : sessions) {
            Number id = (Number) row.get("id");
            String key = String.valueOf(row.get("session_key"));
            jdbc.update("DELETE FROM feishu_bot_message WHERE agent_session_key=? OR binding_id IN (SELECT id FROM feishu_bot_binding WHERE session_id=?)", key, id.longValue());
            jdbc.update("DELETE FROM feishu_bot_binding WHERE session_id=?", id.longValue());
            jdbc.update("DELETE FROM agent_mcp_audit WHERE session_key=?", key);
            jdbc.update("DELETE FROM agent_recommendation WHERE run_id IN (SELECT id FROM agent_run WHERE session_id=?)", id.longValue());
            jdbc.update("DELETE FROM agent_message WHERE session_id=?", id.longValue());
            jdbc.update("DELETE FROM agent_run WHERE session_id=?", id.longValue());
            jdbc.update("DELETE FROM agent_session WHERE id=? AND status='DELETED'", id.longValue());
        }
    }
}

package com.company.skillplatform.collabproject.progress.application;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Local, idempotent progress ledger. Called inside the surrounding business transaction. */
@Service
public class CollabProgressEventService {
    private final JdbcTemplate jdbc;

    public CollabProgressEventService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureDocument(long requirementId, boolean newRequirement) {
        jdbc.update("insert ignore into collab_requirement_progress_doc(requirement_id,bootstrap_state,bootstrap_version,bootstrap_completed_at) values(?,?,?,?)",
                requirementId, newRequirement ? "COMPLETE" : "PENDING", newRequirement ? 1 : 0,
                newRequirement ? Instant.now() : null);
    }

    public boolean record(long requirementId, Long stageId, Integer attempt, String eventType,
            String sourceType, String sourceId, Long actorId, Instant occurredAt, String summary, String targetUrl) {
        String key = sourceType.toLowerCase() + ":" + sourceId + ":" + eventType.toLowerCase();
        int inserted = jdbc.update("insert ignore into collab_requirement_progress_event(requirement_id,stage_id,attempt_no,event_type,event_key,source_type,source_id,actor_user_id,occurred_at,summary,target_url) values(?,?,?,?,?,?,?,?,?,?,?)",
                requirementId, stageId, attempt, eventType, key, sourceType, sourceId, actorId, occurredAt,
                summary.length() > 1200 ? summary.substring(0, 1200) : summary, targetUrl);
        if (inserted > 0) dirty(requirementId);
        return inserted > 0;
    }

    public void dirty(long requirementId) {
        jdbc.update("update collab_requirement_progress_doc set desired_revision=desired_revision+1,time_updated=now(3) where requirement_id=?", requirementId);
    }
}

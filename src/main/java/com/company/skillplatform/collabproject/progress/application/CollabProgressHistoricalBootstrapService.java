package com.company.skillplatform.collabproject.progress.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuilds only events backed by persisted business facts. Safe to repeat after interruption. */
@Component
public class CollabProgressHistoricalBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(CollabProgressHistoricalBootstrapService.class);
    private final JdbcTemplate jdbc;
    private final CollabProgressEventService events;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String groupIds;
    private final String requirementIds;

    public CollabProgressHistoricalBootstrapService(JdbcTemplate jdbc, CollabProgressEventService events,
            TransactionTemplate tx, @Value("${skill-platform.collab-progress.backfill-enabled:true}") boolean enabled,
            @Value("${skill-platform.collab-progress.group-ids:}") String groupIds,
            @Value("${skill-platform.collab-progress.requirement-ids:}") String requirementIds) {
        this.jdbc = jdbc; this.events = events; this.tx = tx; this.enabled = enabled; this.groupIds = groupIds; this.requirementIds = requirementIds;
    }

    @Scheduled(initialDelayString = "${skill-platform.collab-progress.backfill-initial-delay-ms:15000}",
            fixedDelayString = "${skill-platform.collab-progress.backfill-interval-ms:3600000}")
    public void scan() {
        if (!enabled) return;
        long after = 0;
        while (true) {
            List<Long> ids = jdbc.query("select p.id from collab_project p where p.group_id is not null and p.status in ('ACTIVE','COMPLETED') and p.id>? and (?='' or find_in_set(cast(p.group_id as char),?)>0) and (?='' or find_in_set(cast(p.id as char),?)>0) order by p.id limit 100",
                    (rs, n) -> rs.getLong(1), after, groupIds, groupIds, requirementIds, requirementIds);
            if (ids.isEmpty()) return;
            for (long id : ids) {
                try { tx.executeWithoutResult(status -> bootstrap(id)); }
                catch (RuntimeException ex) { log.warn("[Yanshu][CollabProgress] bootstrap failed requirementId={}", id, ex); }
            }
            after = ids.get(ids.size() - 1);
        }
    }

    public void bootstrap(long id) {
        events.ensureDocument(id, false);
        Map<String, Object> doc = jdbc.queryForMap("select bootstrap_state from collab_requirement_progress_doc where requirement_id=? for update", id);
        boolean complete = "COMPLETE".equals(doc.get("bootstrap_state"));
        if (!complete) jdbc.update("update collab_requirement_progress_doc set bootstrap_state='RUNNING',bootstrap_started_at=now(3),time_updated=now(3) where requirement_id=?", id);
        Map<String, Object> project = jdbc.queryForMap("select time_created,created_by from collab_project where id=?", id);
        events.record(id, null, null, "REQUIREMENT_CREATED", "PROJECT", Long.toString(id),
                number(project.get("created_by")), instant(project.get("time_created")), "需求创建", null);

        for (Map<String, Object> row : jdbc.queryForList("select id,attempt_no,title,started_at,completed_at from collab_stage where project_id=?", id)) {
            long stageId = ((Number) row.get("id")).longValue();
            Integer attempt = ((Number) row.get("attempt_no")).intValue();
            Integer activationEvents = jdbc.queryForObject("select count(*) from collab_stage_event where stage_id=? and event_type in ('STAGE_ACTIVATED','ASSIGNMENT_RESTORED')", Integer.class, stageId);
            if (row.get("started_at") != null && (activationEvents == null || activationEvents == 0)) events.record(id, stageId, null, "STAGE_ACTIVATED", "STAGE_TIMESTAMP",
                    stageId + ":started", null, instant(row.get("started_at")), "阶段已启动（据阶段记录的开始时间）：" + row.get("title"), null);
            if (row.get("completed_at") != null) events.record(id, stageId, attempt, "STAGE_COMPLETED", "STAGE_TIMESTAMP",
                    stageId + ":completed", null, instant(row.get("completed_at")), "阶段已完成（据阶段记录的完成时间）：" + row.get("title"), null);
        }
        for (Map<String, Object> row : jdbc.queryForList("select e.id,e.stage_id,e.attempt_no,e.event_type,e.actor_user_id,e.created_at from collab_stage_event e where e.project_id=? and e.event_type not in ('APPROVED','REJECTED','REVIEWER_APPROVED','SUBMITTED','FEISHU_PUBLISHED')", id))
            events.record(id, number(row.get("stage_id")), intNumber(row.get("attempt_no")), String.valueOf(row.get("event_type")),
                    "FLOW", String.valueOf(row.get("id")), number(row.get("actor_user_id")), instant(row.get("created_at")),
                    "流程记录：" + row.get("event_type"), null);
        for (Map<String, Object> row : jdbc.queryForList("select sub.id,sub.stage_id,sub.attempt_no,sub.submitted_by,sub.submitted_at from collab_submission sub join collab_stage s on s.id=sub.stage_id where s.project_id=?", id))
            events.record(id, number(row.get("stage_id")), intNumber(row.get("attempt_no")), "ARTIFACT_SUBMITTED", "SUBMISSION",
                    String.valueOf(row.get("id")), number(row.get("submitted_by")), instant(row.get("submitted_at")), "产物提交", null);
        for (Map<String, Object> row : jdbc.queryForList("select a.id,sub.stage_id,sub.attempt_no,a.reviewer_user_id,a.state,a.decision_comment,a.decided_at from collab_approval a join collab_submission sub on sub.id=a.submission_id join collab_stage s on s.id=sub.stage_id where s.project_id=? and a.state in ('APPROVED','REJECTED') and a.decided_at is not null", id)) {
            String decision = String.valueOf(row.get("state"));
            String comment = row.get("decision_comment") == null ? "" : String.valueOf(row.get("decision_comment"));
            events.record(id, number(row.get("stage_id")), intNumber(row.get("attempt_no")), "APPROVED".equals(decision) ? "APPROVAL_DECISION" : "APPROVAL_REJECTED",
                    "APPROVAL", String.valueOf(row.get("id")), number(row.get("reviewer_user_id")), instant(row.get("decided_at")),
                    ("APPROVED".equals(decision) ? "审批通过" : "审批驳回") + (comment.isBlank() ? "" : "：" + comment), null);
        }
        for (Map<String, Object> row : jdbc.queryForList("select pub.id,v.stage_id,v.attempt_no,pub.published_at,pub.url from collab_feishu_publication pub join collab_artifact_version v on v.id=pub.artifact_version_id join collab_stage s on s.id=v.stage_id where s.project_id=? and pub.publish_status='PUBLISHED' and pub.published_at is not null", id))
            events.record(id, number(row.get("stage_id")), intNumber(row.get("attempt_no")), "ARTIFACT_PUBLISHED", "PUBLICATION",
                    String.valueOf(row.get("id")), null, instant(row.get("published_at")), "飞书产物发布成功", (String) row.get("url"));
        for (Map<String, Object> row : jdbc.queryForList("select id,stage_id,attempt_no,completed_at from collab_asdm_package where requirement_id=? and package_status='PUBLISHED' and completed_at is not null", id))
            events.record(id, number(row.get("stage_id")), intNumber(row.get("attempt_no")), "ARTIFACT_PUBLISHED", "ASDM_PACKAGE",
                    String.valueOf(row.get("id")), null, instant(row.get("completed_at")), "ASDM 产物包发布成功", null);
        if (!complete) {
            jdbc.update("update collab_requirement_progress_doc set bootstrap_state='COMPLETE',bootstrap_version=1,bootstrap_completed_at=now(3),bootstrap_last_error_code=null,time_updated=now(3) where requirement_id=?", id);
            events.dirty(id);
        }
    }

    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static Integer intNumber(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        return (Instant) value;
    }
}

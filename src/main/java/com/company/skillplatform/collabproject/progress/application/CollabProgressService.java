package com.company.skillplatform.collabproject.progress.application;

import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollabProgressService {
    private final JdbcTemplate jdbc;
    private final CollabProjectAuthorizationService authorization;
    private final CollabProgressEventService events;
    private final CollabProgressHistoricalBootstrapService bootstrap;
    @Autowired(required = false)
    private com.company.skillplatform.collabproject.progress.infrastructure.FeishuProgressDocGateway gateway;

    public CollabProgressService(JdbcTemplate jdbc, CollabProjectAuthorizationService authorization,
            CollabProgressEventService events, CollabProgressHistoricalBootstrapService bootstrap) {
        this.jdbc = jdbc; this.authorization = authorization; this.events = events; this.bootstrap = bootstrap;
    }

    @Transactional
    public ProgressView get(String key, long actorId) {
        var ref = authorization.requireMemberOrThrow(key, actorId);
        if (ref.groupId() == null) return null;
        bootstrap.bootstrap(ref.projectId());
        return jdbc.queryForObject("select status,document_url,last_synced_at,desired_revision,applied_revision,last_error_code,bootstrap_state,bootstrap_completed_at from collab_requirement_progress_doc where requirement_id=?",
                (rs, n) -> new ProgressView(key, rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getLong(4), rs.getLong(5),
                        rs.getLong(4) > rs.getLong(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()), ref.projectId());
    }

    @Transactional
    public ProgressView refresh(String key, long actorId, String idempotencyKey) {
        var ref = authorization.requireRequirementManager(key, actorId);
        if (ref.groupId() == null) return null;
        bootstrap.bootstrap(ref.projectId());
        jdbc.queryForObject("select id from collab_requirement_progress_doc where requirement_id=? for update", Long.class, ref.projectId());
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw new com.company.skillplatform.common.application.BusinessException("PROGRESS_IDEMPOTENCY_REQUIRED", "A valid Idempotency-Key is required", org.springframework.http.HttpStatus.BAD_REQUEST);
        Integer repeated = jdbc.queryForObject("select count(*) from collab_requirement_progress_refresh where requirement_id=? and idempotency_key=?", Integer.class, ref.projectId(), idempotencyKey);
        if (repeated != null && repeated > 0) return get(key, actorId);
        Integer recent = jdbc.queryForObject("select count(*) from collab_requirement_progress_refresh where requirement_id=? and requested_at>date_sub(now(3),interval 10 second)", Integer.class, ref.projectId());
        if (recent != null && recent > 0) throw new com.company.skillplatform.common.application.BusinessException("PROGRESS_REFRESH_RATE_LIMITED", "Refresh is limited to once every 10 seconds", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        int inserted = jdbc.update("insert ignore into collab_requirement_progress_refresh(requirement_id,idempotency_key) values(?,?)", ref.projectId(), idempotencyKey);
        if (inserted > 0) {
            jdbc.update("update collab_requirement_progress_doc set status=case when document_token is null then 'PENDING_CREATE' else 'WRITING' end,last_error_code=null where requirement_id=? and status='AUTH_REQUIRED'", ref.projectId());
            events.dirty(ref.projectId());
        }
        return get(key, actorId);
    }

    public ProgressView reconcile(String key, long actorId, boolean confirmMissing) {
        var ref = authorization.requireMemberOrThrow(key, actorId);
        if (ref.groupId() == null) return null;
        if (!authorization.isAdmin(actorId)) throw new com.company.skillplatform.common.application.BusinessException(
                "PROGRESS_ADMIN_REQUIRED", "Administrator permission is required", org.springframework.http.HttpStatus.FORBIDDEN);
        bootstrap.bootstrap(ref.projectId());
        var row = jdbc.queryForMap("select d.document_token,d.status,d.time_updated,p.name,b.wiki_space_id,b.wiki_parent_node_token,b.publish_actor_user_id from collab_requirement_progress_doc d join collab_project p on p.id=d.requirement_id join collab_feishu_binding b on b.project_id=p.id where d.requirement_id=?", ref.projectId());
        if (row.get("document_token") != null) {
            String doc = String.valueOf(row.get("document_token"));
            if (gateway.block(((Number)row.get("publish_actor_user_id")).longValue(), doc, doc).isMissingNode())
                throw new com.company.skillplatform.common.application.BusinessException("PROGRESS_REMOTE_DOC_MISSING", "Remote document is missing", org.springframework.http.HttpStatus.CONFLICT);
            jdbc.update("update collab_requirement_progress_doc set status='WRITING',last_error_code=null where requirement_id=?", ref.projectId());
        } else {
            String title = "研发进度跟踪｜" + row.get("name");
            String page = ""; com.fasterxml.jackson.databind.JsonNode found = null;
            do {
                var data = gateway.wikiChildren(((Number)row.get("publish_actor_user_id")).longValue(), String.valueOf(row.get("wiki_space_id")), String.valueOf(row.get("wiki_parent_node_token")), page);
                for (var item : data.path("items")) if (title.equals(item.path("title").asText())) {
                    if (found != null) throw new com.company.skillplatform.common.application.BusinessException("PROGRESS_DUPLICATE_REMOTE_DOC", "Multiple matching progress documents exist", org.springframework.http.HttpStatus.CONFLICT);
                    found = item;
                }
                if (!data.path("has_more").asBoolean(false)) break;
                page = data.path("page_token").asText("");
                if (page.isBlank()) throw new com.company.skillplatform.common.application.BusinessException("PROGRESS_WIKI_PAGE_INVALID", "Wiki pagination is incomplete", org.springframework.http.HttpStatus.BAD_GATEWAY);
            } while (true);
            if (found != null) {
                String node = found.path("node_token").asText(""); String doc = found.path("obj_token").asText("");
                if (node.isBlank() || doc.isBlank()) throw new com.company.skillplatform.common.application.BusinessException("PROGRESS_REMOTE_TOKEN_MISSING", "Matching document has no tokens", org.springframework.http.HttpStatus.CONFLICT);
                jdbc.update("update collab_requirement_progress_doc set wiki_node_token=?,document_token=?,document_url=?,status='WRITING',last_error_code=null where requirement_id=?", node, doc, found.path("url").asText("https://www.feishu.cn/wiki/"+node), ref.projectId());
            } else if (confirmMissing) {
                jdbc.update("update collab_requirement_progress_doc set status='PENDING_CREATE',last_error_code=null where requirement_id=? and document_token is null", ref.projectId());
            } else throw new com.company.skillplatform.common.application.BusinessException("PROGRESS_CONFIRM_MISSING_REQUIRED", "Confirm the remote document is absent before retrying creation", org.springframework.http.HttpStatus.CONFLICT);
        }
        events.dirty(ref.projectId());
        return get(key, actorId);
    }

    public record ProgressView(String requirementKey, String status, String documentUrl, Instant lastSyncedAt,
            long desiredRevision, long appliedRevision, boolean outOfSync, String lastErrorCode,
            String bootstrapState, Instant bootstrapCompletedAt) {}
}

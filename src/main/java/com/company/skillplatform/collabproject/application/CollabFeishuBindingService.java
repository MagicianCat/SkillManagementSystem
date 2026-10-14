package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CollabFeishuBindingService {
    private final JdbcTemplate jdbc;
    private final CollabProjectAuthorizationService auth;
    private final FeishuDocumentMcpProxy documents;
    private final CollabFeishuOutbox outbox;
    private final TransactionTemplate transactions;

    public CollabFeishuBindingService(JdbcTemplate jdbc, CollabProjectAuthorizationService auth,
                                      FeishuDocumentMcpProxy documents, CollabFeishuOutbox outbox,
                                      TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.documents = documents;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public BindingView get(String key, Long actor) {
        long id = auth.requireMemberOrThrow(key, actor).projectId();
        return view(id);
    }

    public BindingView configureWikiRoot(String key, Long actor, String url) {
        long id = auth.requireOwner(key, actor).projectId();
        WikiTarget target = validateWikiRoot(actor, url);
        return transactions.execute(status -> {
            Map<String, Object> previous = jdbc.queryForMap("SELECT wiki_parent_node_token,wiki_root_node_token FROM collab_feishu_binding WHERE project_id=? FOR UPDATE", id);
            if (previous.get("wiki_parent_node_token") != null && !target.nodeToken().equals(previous.get("wiki_root_node_token")))
                throw new BusinessException("COLLAB_WIKI_TARGET_LOCKED", "Project Wiki is already provisioned", HttpStatus.CONFLICT);
            jdbc.update("UPDATE collab_feishu_binding SET wiki_space_id=?,wiki_root_node_token=?,publish_actor_user_id=?,last_error=NULL,time_updated=NOW(3) WHERE project_id=?",
                    target.spaceId(), target.nodeToken(), actor, id);
            outbox.enqueueWikiFolder(id);
            return view(id);
        });
    }

    public WikiTarget validateWikiRoot(Long actor, String url) {
        JsonNode target = documents.validatePublishTarget(actor, url);
        String space = target.path("spaceId").asText("");
        String root = target.path("nodeToken").asText("");
        if (space.isBlank() || root.isBlank()) throw new BusinessException("COLLAB_WIKI_TARGET_INVALID", "Wiki target is missing identifiers", HttpStatus.BAD_REQUEST);
        return new WikiTarget(space, root);
    }

    public BindingView retry(String key, Long actor) {
        long id = auth.requireOwner(key, actor).projectId();
        return transactions.execute(status -> {
            jdbc.update("UPDATE collab_outbox SET status='PENDING',attempts=CASE WHEN status='FAILED' THEN 0 ELSE attempts END,"
                    + "next_attempt_at=NOW(3),last_error=NULL,time_updated=NOW(3) "
                    + "WHERE project_id=? AND status IN ('FAILED','PENDING') AND event_type NOT IN ('CREATE_CHAT','CREATE_WIKI_FOLDER')", id);
            jdbc.update("UPDATE collab_feishu_publication pv JOIN collab_artifact_version v ON v.id=pv.artifact_version_id "
                    + "JOIN collab_stage s ON s.id=v.stage_id SET pv.publish_status='PENDING',pv.last_error=NULL,pv.time_updated=NOW(3) "
                    + "WHERE s.project_id=? AND pv.publish_status='PUBLISH_FAILED' "
                    + "AND EXISTS(SELECT 1 FROM collab_outbox o WHERE o.project_id=s.project_id AND o.aggregate_id=v.id "
                    + "AND o.event_type='PUBLISH_ARTIFACT' AND o.status='PENDING')", id);
            return view(id);
        });
    }

    private BindingView view(long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT chat_id,wiki_space_id,wiki_parent_node_token,status,last_error FROM collab_feishu_binding WHERE project_id=?", id);
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM collab_outbox WHERE project_id=? AND status IN ('PENDING','PROCESSING')", Integer.class, id);
        return new BindingView(String.valueOf(row.get("status")), (String) row.get("chat_id"),
                (String) row.get("wiki_space_id"), (String) row.get("wiki_parent_node_token"),
                (String) row.get("last_error"), pending == null ? 0 : pending);
    }

    public record BindingView(String status, String chatId, String wikiSpaceId, String wikiParentNodeToken,
                              String lastError, int pendingTasks) {}
    public record WikiTarget(String spaceId, String nodeToken) {}
}

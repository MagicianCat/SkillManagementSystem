package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Creates one chat and one Wiki folder per published Group, then keeps chat membership in sync. */
@Component
public class GroupFeishuWorker {
    private static final Logger log = LoggerFactory.getLogger(GroupFeishuWorker.class);
    private final JdbcTemplate jdbc;
    private final CollabFeishuChatGateway chats;
    private final FeishuDocumentMcpProxy documents;
    private final GroupFeishuOutbox outbox;

    public GroupFeishuWorker(JdbcTemplate jdbc, CollabFeishuChatGateway chats,
                             FeishuDocumentMcpProxy documents, GroupFeishuOutbox outbox) {
        this.jdbc = jdbc;
        this.chats = chats;
        this.documents = documents;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${collab.feishu.group-worker-delay-ms:10000}")
    public void drain() {
        recoverExpiredClaims();
        List<Long> ready = jdbc.query("SELECT id FROM collab_group_outbox WHERE status='PENDING' "
                + "AND next_attempt_at<=NOW(3) ORDER BY id LIMIT 10", (rs, row) -> rs.getLong(1));
        for (Long id : ready) process(id);
    }

    private void recoverExpiredClaims() {
        List<Map<String, Object>> stale = jdbc.queryForList("SELECT id,group_id,event_type FROM collab_group_outbox "
                + "WHERE status='PROCESSING' AND claimed_at<DATE_SUB(NOW(3),INTERVAL 5 MINUTE) ORDER BY id LIMIT 20");
        for (Map<String, Object> event : stale) {
            long id = ((Number) event.get("id")).longValue();
            long groupId = ((Number) event.get("group_id")).longValue();
            String type = String.valueOf(event.get("event_type"));
            if ("CREATE_CHAT".equals(type) || "CREATE_WIKI_FOLDER".equals(type)) {
                String column = "CREATE_CHAT".equals(type) ? "chat_id" : "wiki_group_node_token";
                Boolean persisted = jdbc.queryForObject("SELECT " + column + " IS NOT NULL FROM collab_group_feishu_binding WHERE group_id=?",
                        Boolean.class, groupId);
                if (Boolean.TRUE.equals(persisted)) {
                    jdbc.update("UPDATE collab_group_outbox SET status='DONE',last_error=NULL,time_updated=NOW(3) "
                            + "WHERE id=? AND status='PROCESSING'", id);
                } else {
                    jdbc.update("UPDATE collab_group_outbox SET status='RECONCILE_REQUIRED',"
                            + "last_error='Worker stopped during remote create; verify Feishu before retrying',time_updated=NOW(3) "
                            + "WHERE id=? AND status='PROCESSING'", id);
                    jdbc.update("UPDATE collab_group_feishu_binding SET status='RECONCILE_REQUIRED',"
                            + "last_error='Worker stopped during remote create',time_updated=NOW(3) WHERE group_id=?", groupId);
                }
            } else {
                jdbc.update("UPDATE collab_group_outbox SET status='PENDING',claimed_at=NULL,next_attempt_at=NOW(3),"
                        + "last_error='Retry after interrupted worker',time_updated=NOW(3) WHERE id=? AND status='PROCESSING'", id);
            }
        }
    }

    public void process(long id) {
        if (jdbc.update("UPDATE collab_group_outbox SET status='PROCESSING',claimed_at=NOW(3),"
                + "attempts=attempts+1,time_updated=NOW(3) WHERE id=? AND status='PENDING' AND next_attempt_at<=NOW(3)", id) == 0) return;
        Map<String, Object> event = jdbc.queryForMap("SELECT group_id,event_type,aggregate_id,attempts FROM collab_group_outbox WHERE id=?", id);
        long groupId = ((Number) event.get("group_id")).longValue();
        long aggregateId = ((Number) event.get("aggregate_id")).longValue();
        String type = String.valueOf(event.get("event_type"));
        try {
            switch (type) {
                case "CREATE_CHAT" -> createChat(groupId);
                case "CREATE_WIKI_FOLDER" -> createWikiFolder(groupId);
                case "ADD_MEMBER" -> syncMember(groupId, aggregateId, true);
                case "REMOVE_MEMBER" -> syncMember(groupId, aggregateId, false);
                default -> throw new IllegalArgumentException("Unknown Group Feishu event");
            }
            jdbc.update("UPDATE collab_group_outbox SET status='DONE',last_error=NULL,time_updated=NOW(3) WHERE id=?", id);
        } catch (Exception error) {
            String message = shortError(error);
            if ("CREATE_CHAT".equals(type) || "CREATE_WIKI_FOLDER".equals(type)) {
                jdbc.update("UPDATE collab_group_outbox SET status='RECONCILE_REQUIRED',last_error=?,time_updated=NOW(3) WHERE id=?", message, id);
                jdbc.update("UPDATE collab_group_feishu_binding SET status='RECONCILE_REQUIRED',last_error=?,time_updated=NOW(3) WHERE group_id=?",
                        message, groupId);
            } else {
                int attempts = ((Number) event.get("attempts")).intValue();
                String status = attempts >= 8 ? "FAILED" : "PENDING";
                long backoff = Math.min(3600, 15L << Math.min(7, attempts));
                jdbc.update("UPDATE collab_group_outbox SET status=?,last_error=?,next_attempt_at=DATE_ADD(NOW(3),INTERVAL ? SECOND),"
                                + "claimed_at=NULL,time_updated=NOW(3) WHERE id=?", status, message, backoff, id);
            }
            log.warn("event=collab.group.feishu.failed groupId={} type={} error={}", groupId, type, message);
        }
    }

    private void createChat(long groupId) {
        Map<String, Object> binding = binding(groupId);
        if (binding.get("chat_id") != null) return;
        String name = jdbc.queryForObject("SELECT name FROM collab_group WHERE id=? AND status='PUBLISHED'", String.class, groupId);
        String chatId = chats.createChat("研枢 · " + name);
        jdbc.update("UPDATE collab_group_feishu_binding SET chat_id=?,status='ACTIVE',last_verified_at=NOW(3),"
                + "last_error=NULL,time_updated=NOW(3) WHERE group_id=?", chatId, groupId);
        List<Long> members = jdbc.query("SELECT m.user_id FROM collab_group_member m JOIN iam_user u ON u.id=m.user_id "
                + "WHERE m.group_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'", (rs, row) -> rs.getLong(1), groupId);
        for (Long memberId : members) {
            try { syncMember(groupId, memberId, true); }
            catch (Exception error) {
                outbox.enqueue(groupId, "ADD_MEMBER", memberId, "group-initial-member:" + groupId + ":" + memberId,
                        Map.of("userId", memberId));
                jdbc.update("UPDATE collab_group_feishu_binding SET status='PARTIAL',last_error='Member synchronization pending',"
                        + "time_updated=NOW(3) WHERE group_id=?", groupId);
            }
        }
    }

    private void createWikiFolder(long groupId) {
        Map<String, Object> binding = binding(groupId);
        if (binding.get("wiki_group_node_token") != null) return;
        Number actor = (Number) binding.get("publish_actor_user_id");
        String space = (String) binding.get("wiki_space_id");
        String root = (String) binding.get("wiki_root_node_token");
        if (actor == null || space == null || root == null) throw new IllegalStateException("Group Wiki root is not configured");
        String name = jdbc.queryForObject("SELECT name FROM collab_group WHERE id=? AND status='PUBLISHED'", String.class, groupId);
        JsonNode node = documents.createWikiDocx(actor.longValue(), space, root, name);
        String nodeToken = node.path("nodeToken").asText("");
        if (nodeToken.isBlank()) throw new IllegalStateException("Feishu did not return Group Wiki node token");
        jdbc.update("UPDATE collab_group_feishu_binding SET wiki_group_node_token=?,status='ACTIVE',last_verified_at=NOW(3),"
                + "last_error=NULL,time_updated=NOW(3) WHERE group_id=?", nodeToken, groupId);
    }

    private void syncMember(long groupId, long userId, boolean add) {
        String chatId = (String) binding(groupId).get("chat_id");
        if (chatId == null || chatId.isBlank()) throw new IllegalStateException("Group chat is not ready");
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM collab_group_member m JOIN iam_user u ON u.id=m.user_id "
                + "WHERE m.group_id=? AND m.user_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'", Integer.class, groupId, userId);
        if (add != (active != null && active > 0)) return; // A newer membership change superseded this event.
        String openId = jdbc.queryForObject("SELECT feishu_open_id FROM iam_user WHERE id=?", String.class, userId);
        if (openId == null || openId.isBlank()) throw new IllegalStateException("Group member has no Feishu open ID");
        if (add) chats.addMember(chatId, openId); else chats.removeMember(chatId, openId);
    }

    private Map<String, Object> binding(long groupId) {
        return jdbc.queryForMap("SELECT chat_id,wiki_space_id,wiki_root_node_token,wiki_group_node_token,publish_actor_user_id "
                + "FROM collab_group_feishu_binding WHERE group_id=?", groupId);
    }

    private String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 1800 ? message.substring(0, 1800) : message;
    }
}

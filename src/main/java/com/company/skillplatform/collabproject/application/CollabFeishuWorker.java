package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Processes committed collaboration events without holding a database lock during network calls. */
@Component
public class CollabFeishuWorker {
    private static final Logger log = LoggerFactory.getLogger(CollabFeishuWorker.class);
    private final JdbcTemplate jdbc;
    private final CollabFeishuChatGateway chats;
    private final FeishuDocumentMcpProxy documents;
    private final String webBaseUrl;
    private final boolean callbacksEnabled;
    private final ObjectMapper mapper;

    public CollabFeishuWorker(JdbcTemplate jdbc, CollabFeishuChatGateway chats, FeishuDocumentMcpProxy documents,
            FeishuProperties feishuProperties, ObjectMapper mapper,
            @Value("${collab.web-base-url:http://127.0.0.1:5173}") String webBaseUrl) {
        this.jdbc = jdbc;
        this.chats = chats;
        this.documents = documents;
        this.webBaseUrl = webBaseUrl.replaceAll("/$", "");
        this.callbacksEnabled = feishuProperties.eventEnabled();
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${collab.feishu.worker-delay-ms:10000}")
    public void drain() {
        recoverExpiredClaims();
        reconcileMissingPublications();
        reconcileMissingReworkReminders();
        List<Long> ids = jdbc.query("SELECT id FROM collab_outbox WHERE status='PENDING' AND next_attempt_at<=NOW(3) ORDER BY id LIMIT 10",
                (rs, row) -> rs.getLong(1));
        for (Long id : ids) process(id);
    }

    /** Ensures a rejected in-flight stage gets its rework card even if it predates this handler. */
    private void reconcileMissingReworkReminders() {
        List<Map<String, Object>> missing = jdbc.queryForList("SELECT s.project_id,s.id stage_id,sub.id submission_id FROM collab_stage s "
                + "JOIN collab_submission sub ON sub.stage_id=s.id AND sub.id=(SELECT MAX(latest.id) FROM collab_submission latest WHERE latest.stage_id=s.id) "
                + "WHERE s.status='REWORK' AND sub.status='REJECTED' "
                + "AND NOT EXISTS(SELECT 1 FROM collab_outbox o WHERE o.unique_dedupe_key=CONCAT('stage-rework:',sub.id)) "
                + "ORDER BY sub.id LIMIT 50");
        for (Map<String, Object> row : missing) {
            long projectId = ((Number) row.get("project_id")).longValue();
            long stageId = ((Number) row.get("stage_id")).longValue();
            long submissionId = ((Number) row.get("submission_id")).longValue();
            jdbc.update("INSERT IGNORE INTO collab_outbox(project_id,event_type,aggregate_type,aggregate_id,unique_dedupe_key,payload_json,status,attempts,next_attempt_at,time_created,time_updated) "
                    + "VALUES(?,'STAGE_REWORK','STAGE',?,?,JSON_OBJECT('stageId',?,'submissionId',?),'PENDING',0,NOW(3),NOW(3),NOW(3))",
                    projectId, stageId, "stage-rework:" + submissionId, stageId, submissionId);
        }
    }

    /** Backfills submissions created before publication was enqueued on submit. */
    private void reconcileMissingPublications() {
        List<Map<String, Object>> missing = jdbc.queryForList("SELECT s.project_id,sub.artifact_version_id FROM collab_submission sub "
                + "JOIN collab_stage s ON s.id=sub.stage_id WHERE sub.status IN ('PENDING_APPROVAL','APPROVED') "
                + "AND sub.id=(SELECT MAX(latest.id) FROM collab_submission latest WHERE latest.stage_id=sub.stage_id) "
                + "AND NOT EXISTS(SELECT 1 FROM collab_feishu_publication pub WHERE pub.artifact_version_id=sub.artifact_version_id) "
                + "ORDER BY sub.id LIMIT 50");
        for (Map<String, Object> row : missing) {
            long projectId = ((Number) row.get("project_id")).longValue();
            long artifactVersionId = ((Number) row.get("artifact_version_id")).longValue();
            jdbc.update("INSERT IGNORE INTO collab_feishu_publication(artifact_version_id,publish_status,time_created,time_updated) "
                    + "VALUES(?,'PENDING',NOW(3),NOW(3))", artifactVersionId);
            jdbc.update("INSERT IGNORE INTO collab_outbox(project_id,event_type,aggregate_type,aggregate_id,unique_dedupe_key,payload_json,status,attempts,next_attempt_at,time_created,time_updated) "
                    + "VALUES(?,'PUBLISH_ARTIFACT','ARTIFACT_VERSION',?,?,JSON_OBJECT('artifactVersionId',?),'PENDING',0,NOW(3),NOW(3),NOW(3))",
                    projectId, artifactVersionId, "publish:" + artifactVersionId, artifactVersionId);
        }
    }

    /** A crashed worker must never recreate a chat, Wiki node or document blindly. */
    private void recoverExpiredClaims() {
        List<Map<String, Object>> stale = jdbc.queryForList("SELECT id,project_id,event_type,aggregate_id FROM collab_outbox "
                + "WHERE status='PROCESSING' AND claimed_at<DATE_SUB(NOW(3),INTERVAL 5 MINUTE) ORDER BY id LIMIT 20");
        for (Map<String, Object> row : stale) {
            long id = ((Number) row.get("id")).longValue();
            long projectId = ((Number) row.get("project_id")).longValue();
            long aggregateId = ((Number) row.get("aggregate_id")).longValue();
            String type = String.valueOf(row.get("event_type"));
            if ("CREATE_CHAT".equals(type) || "CREATE_WIKI_FOLDER".equals(type) || "PUBLISH_ARTIFACT".equals(type)) {
                boolean persisted = switch (type) {
                    case "CREATE_CHAT" -> jdbc.queryForObject("SELECT chat_id IS NOT NULL FROM collab_feishu_binding WHERE project_id=?", Boolean.class, projectId);
                    case "CREATE_WIKI_FOLDER" -> jdbc.queryForObject("SELECT wiki_parent_node_token IS NOT NULL FROM collab_feishu_binding WHERE project_id=?", Boolean.class, projectId);
                    default -> "PUBLISHED".equals(jdbc.queryForObject("SELECT publish_status FROM collab_feishu_publication WHERE artifact_version_id=?", String.class, aggregateId));
                };
                if (persisted) {
                    jdbc.update("UPDATE collab_outbox SET status='DONE',last_error=NULL,time_updated=NOW(3) WHERE id=? AND status='PROCESSING'", id);
                    continue;
                }
                int changed = jdbc.update("UPDATE collab_outbox SET status='RECONCILE_REQUIRED',last_error='Worker stopped during a remote create; verify the Feishu result',time_updated=NOW(3) "
                        + "WHERE id=? AND status='PROCESSING' AND claimed_at<DATE_SUB(NOW(3),INTERVAL 5 MINUTE)", id);
                if (changed == 0) continue;
                if ("PUBLISH_ARTIFACT".equals(type))
                    jdbc.update("UPDATE collab_feishu_publication SET publish_status='RECONCILE_REQUIRED',last_error='Worker stopped during publication',time_updated=NOW(3) "
                            + "WHERE artifact_version_id=? AND publish_status<>'PUBLISHED'", aggregateId);
                else jdbc.update("UPDATE collab_feishu_binding SET status='RECONCILE_REQUIRED',last_error='Worker stopped during Feishu provisioning',time_updated=NOW(3) WHERE project_id=?", projectId);
            } else {
                jdbc.update("UPDATE collab_outbox SET status='PENDING',next_attempt_at=NOW(3),claimed_at=NULL,last_error='Retrying after worker interruption',time_updated=NOW(3) "
                        + "WHERE id=? AND status='PROCESSING' AND claimed_at<DATE_SUB(NOW(3),INTERVAL 5 MINUTE)", id);
            }
        }
    }

    public void process(Long id) {
        int claimed = jdbc.update("UPDATE collab_outbox SET status='PROCESSING',claimed_at=NOW(3),attempts=attempts+1,time_updated=NOW(3) "
                + "WHERE id=? AND status='PENDING' AND next_attempt_at<=NOW(3)", id);
        if (claimed == 0) return;
        Map<String, Object> row = jdbc.queryForMap("SELECT project_id,event_type,aggregate_type,aggregate_id,attempts,id FROM collab_outbox WHERE id=?", id);
        long projectId = ((Number) row.get("project_id")).longValue();
        String type = String.valueOf(row.get("event_type"));
        long aggregateId = ((Number) row.get("aggregate_id")).longValue();
        try {
            switch (type) {
                case "CREATE_CHAT" -> createChat(projectId);
                case "ADD_MEMBER" -> syncMember(projectId, aggregateId, true);
                case "REMOVE_MEMBER" -> syncMember(projectId, aggregateId, false);
                case "APPROVAL_REMINDER" -> approvalReminder(projectId, aggregateId, "APPROVAL".equals(row.get("aggregate_type")));
                case "STAGE_HANDOFF" -> stageHandoff(projectId, aggregateId, id);
                case "STAGE_ASSIGNMENT" -> stageAssignment(projectId, aggregateId, id);
                case "STAGE_REWORK" -> stageRework(projectId, aggregateId, id);
                case "CREATE_WIKI_FOLDER" -> createWikiFolder(projectId);
                case "PUBLISH_ARTIFACT" -> publish(projectId, aggregateId);
                default -> throw new IllegalArgumentException("Unknown collaboration event");
            }
            jdbc.update("UPDATE collab_outbox SET status='DONE',last_error=NULL,time_updated=NOW(3) WHERE id=?", id);
        } catch (Exception error) {
            String message = shortError(error);
            if ("CREATE_CHAT".equals(type) || "CREATE_WIKI_FOLDER".equals(type) ||
                    ("PUBLISH_ARTIFACT".equals(type) && publicationMayExist(aggregateId))) {
                if ("CREATE_CHAT".equals(type) || "CREATE_WIKI_FOLDER".equals(type))
                    jdbc.update("UPDATE collab_feishu_binding SET status='RECONCILE_REQUIRED',last_error=?,time_updated=NOW(3) WHERE project_id=?", message, projectId);
                if ("PUBLISH_ARTIFACT".equals(type))
                    jdbc.update("UPDATE collab_feishu_publication SET publish_status='RECONCILE_REQUIRED',last_error=?,time_updated=NOW(3) WHERE artifact_version_id=?", message, aggregateId);
                jdbc.update("UPDATE collab_outbox SET status='RECONCILE_REQUIRED',last_error=?,time_updated=NOW(3) WHERE id=?", message, id);
            } else {
                int attempts = ((Number) row.get("attempts")).intValue();
                String status = attempts >= 8 ? "FAILED" : "PENDING";
                long backoff = Math.min(3600, 15L << Math.min(7, attempts));
                jdbc.update("UPDATE collab_outbox SET status=?,last_error=?,next_attempt_at=DATE_ADD(NOW(3),INTERVAL ? SECOND),time_updated=NOW(3) WHERE id=?",
                        status, message, backoff, id);
                if ("PUBLISH_ARTIFACT".equals(type))
                    jdbc.update("UPDATE collab_feishu_publication SET publish_status=?,last_error=?,attempt_count=attempt_count+1,time_updated=NOW(3) WHERE artifact_version_id=?",
                            status.equals("FAILED") ? "PUBLISH_FAILED" : "PENDING", message, aggregateId);
            }
        }
    }

    private void createChat(long projectId) {
        Map<String, Object> binding = binding(projectId);
        String existing = (String) binding.get("chat_id");
        if (existing != null && !existing.isBlank()) return;
        String name = jdbc.queryForObject("SELECT name FROM collab_project WHERE id=?", String.class, projectId);
        String chatId = chats.createChat("研枢 · " + name);
        jdbc.update("UPDATE collab_feishu_binding SET chat_id=?,status='ACTIVE',last_error=NULL,last_verified_at=NOW(3),time_updated=NOW(3) WHERE project_id=?",
                chatId, projectId);
        List<String> openIds = jdbc.query("SELECT DISTINCT u.feishu_open_id FROM collab_project_member m JOIN iam_user u ON u.id=m.user_id "
                + "WHERE m.project_id=? AND m.status='ACTIVE' AND u.feishu_open_id IS NOT NULL AND u.feishu_open_id<>''",
                (rs, n) -> rs.getString(1), projectId);
        for (String openId : openIds) {
            try { chats.addMember(chatId, openId); }
            catch (Exception ignored) {
                jdbc.update("UPDATE collab_feishu_binding SET status='PARTIAL',last_error='Member synchronization pending',time_updated=NOW(3) WHERE project_id=?", projectId);
            }
        }
    }

    private void syncMember(long projectId, long userId, boolean add) {
        String chatId = chatId(projectId);
        String openId = jdbc.queryForObject("SELECT feishu_open_id FROM iam_user WHERE id=?", String.class, userId);
        if (openId == null || openId.isBlank()) throw new IllegalStateException("Member has no Feishu open ID");
        if (add) chats.addMember(chatId, openId); else chats.removeMember(chatId, openId);
    }

    private void approvalReminder(long projectId, long aggregateId, boolean aggregateIsApproval) {
        String chatId = chatId(projectId);
        String aggregatePredicate = aggregateIsApproval ? "a.id=?" : "b.id=?";
        Map<String, Object> row = jdbc.queryForMap("SELECT p.name,p.project_key,s.stage_key,a.state,a.version_no,a.id approval_id,b.id submission_id,u.display_name,u.feishu_open_id,art.title,pv.url feishu_url,pv.publish_status,v.git_evidence_json FROM collab_submission b "
                + "JOIN collab_stage s ON s.id=b.stage_id JOIN collab_project p ON p.id=s.project_id "
                + "JOIN collab_artifact_version v ON v.id=b.artifact_version_id JOIN collab_artifact art ON art.id=v.artifact_id "
                + "JOIN collab_approval a ON a.submission_id=b.id JOIN iam_user u ON u.id=a.reviewer_user_id "
                + "LEFT JOIN collab_feishu_publication pv ON pv.artifact_version_id=v.id WHERE " + aggregatePredicate + " AND p.id=? ORDER BY a.id LIMIT 1",
                aggregateId, projectId);
        if (!"PENDING".equals(row.get("state"))) return;
        if (!"PUBLISHED".equals(row.get("publish_status")) || row.get("feishu_url") == null)
            throw new IllegalStateException("Approval reminder waits for the Feishu artifact document");
        String projectKey = String.valueOf(row.get("project_key"));
        String stageKey = String.valueOf(row.get("stage_key"));
        String reviewUrl = webBaseUrl + "/collab-projects/" + projectKey + "/stages/" + stageKey;
        long approvalVersion = ((Number) row.get("version_no")).longValue();
        long submissionId = ((Number) row.get("submission_id")).longValue();
        Object reviewerOpenIdValue = row.get("feishu_open_id");
        String reviewerOpenId = reviewerOpenIdValue == null ? "" : reviewerOpenIdValue.toString().trim();
        String reviewer = reviewerOpenId.isEmpty() ? String.valueOf(row.get("display_name"))
                : "<at id=" + reviewerOpenId + "></at>";
        List<Object> elements = new ArrayList<>();
        String gitLinks = "";
        Object gitJson = row.get("git_evidence_json");
        if (gitJson != null && !gitJson.toString().isBlank()) {
            try {
                JsonNode evidence = mapper.readTree(gitJson.toString());
                String repositoryUrl = evidence.path("repositoryUrl").asText("");
                String commitUrl = evidence.path("commitOrPullRequestUrl").asText("");
                if (!repositoryUrl.isBlank()) gitLinks += "\n[Git 仓库](" + repositoryUrl + ")";
                if (!commitUrl.isBlank()) gitLinks += "\n[Commit / PR](" + commitUrl + ")";
            } catch (Exception ex) {
                log.warn("Could not read Git evidence for approval submission {}", submissionId, ex);
            }
        }
        elements.add(Map.of("tag", "markdown", "content", "项目 **" + row.get("name") + "**\n阶段 **" + stageKey + "**\n产物 **" + row.get("title") + "**\n待 " + reviewer + " 审批。\n[在飞书中查看待审批产物](" + row.get("feishu_url") + ")" + gitLinks));
        if (callbacksEnabled) {
            elements.add(Map.of("tag", "button", "type", "primary", "element_id", "collab_approval_approve",
                    "name", "collab_approve|" + submissionId + "|" + approvalVersion,
                    "text", Map.of("tag", "plain_text", "content", "批准"),
                    "behaviors", List.of(Map.of("type", "callback", "value", Map.of("action", "collab_approve",
                            "submissionId", submissionId, "approvalVersion", approvalVersion)))));
        }
        elements.add(Map.of("tag", "button", "type", "default", "element_id", "collab_approval_details",
                "text", Map.of("tag", "plain_text", "content", "查看详情 / 驳回"),
                "behaviors", List.of(Map.of("type", "open_url", "default_url", reviewUrl,
                        "pc_url", reviewUrl, "ios_url", reviewUrl, "android_url", reviewUrl))));
        chats.sendInteractive(chatId, Map.of("schema", "2.0", "body", Map.of("elements", elements)),
                "collab-approval-" + row.get("approval_id"));
    }

    private void stageHandoff(long projectId, long stageId, long outboxId) {
        String chatId = chatId(projectId);
        Map<String, Object> row = jdbc.queryForMap("SELECT p.name,p.project_key,s.id stage_id,s.stage_key,s.title stage_title,s.stage_order,s.attempt_no,s.git_required,"
                + "u.display_name,u.feishu_open_id "
                + "FROM collab_stage s JOIN collab_project p ON p.id=s.project_id JOIN iam_user u ON u.id=s.owner_user_id "
                + "WHERE s.id=? AND p.id=?", stageId, projectId);
        String openId = String.valueOf(row.get("feishu_open_id"));
        if (openId.isBlank() || "null".equals(openId))
            throw new IllegalStateException("Next stage owner has no Feishu open ID");

        String projectKey = String.valueOf(row.get("project_key"));
        String stageKey = String.valueOf(row.get("stage_key"));
        String stageTitle = String.valueOf(row.get("stage_title"));
        int stageOrder = ((Number) row.get("stage_order")).intValue();
        int attemptNo = ((Number) row.get("attempt_no")).intValue();
        String stageUrl = webBaseUrl + "/collab-projects/" + projectKey + "/stages/" + stageKey;
        String submitPrompt = CollabStagePromptFactory.submit(projectKey, stageTitle, stageId, attemptNo,
                Boolean.TRUE.equals(row.get("git_required")));
        List<Map<String, Object>> assignedReviewers = jdbc.queryForList("SELECT DISTINCT u.display_name,u.feishu_open_id FROM iam_user u JOIN ("
                + "SELECT reviewer_user_id FROM collab_stage WHERE id=? AND reviewer_user_id IS NOT NULL UNION "
                + "SELECT reviewer_user_id FROM collab_stage_additional_reviewer WHERE stage_id=?) r ON r.reviewer_user_id=u.id ORDER BY u.display_name", stageId, stageId);
        String reviewerMentions = assignedReviewers.isEmpty() ? "" : "\n审批人：" + assignedReviewers.stream()
                .map(reviewer -> reviewer.get("feishu_open_id") == null ? String.valueOf(reviewer.get("display_name"))
                        : "<at id=" + reviewer.get("feishu_open_id") + "></at>")
                .collect(java.util.stream.Collectors.joining("、"));

        List<Object> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", "项目 **" + row.get("name") + "**\n"
                + "阶段 **" + stageTitle + "** 已就绪，现由 <at id="
                + openId + "></at> 负责（第 " + stageOrder + " 阶段）。" + reviewerMentions));
        if (stageOrder > 1) {
            String contextPrompt = CollabStagePromptFactory.context(projectKey, stageTitle);
            elements.add(Map.of("tag", "markdown", "content",
                    promptMarkdown("获取前序阶段产物提示词", contextPrompt)));
            if (callbacksEnabled) elements.add(promptButton(projectKey, stageId, attemptNo, "CONTEXT"));
        }
        elements.add(Map.of("tag", "markdown", "content",
                promptMarkdown("阶段完成后推送提示词", submitPrompt)
                        + "\n请先把 {local file} 占位符替换为产物在本机上的实际文件路径。"));
        if (callbacksEnabled) elements.add(promptButton(projectKey, stageId, attemptNo, "SUBMIT"));
        elements.add(Map.of("tag", "markdown", "content", "[打开阶段页面](" + stageUrl + ")"));
        chats.sendInteractive(chatId, Map.of("schema", "2.0", "body", Map.of("elements", elements)),
                "collab-stage-handoff-" + outboxId);
    }

    private void stageAssignment(long projectId, long stageId, long outboxId) {
        String chatId = chatId(projectId);
        Map<String, Object> row = jdbc.queryForMap("SELECT p.name,p.project_key,s.stage_key,s.title stage_title,s.stage_order,s.status,s.id stage_id "
                + "FROM collab_stage s JOIN collab_project p ON p.id=s.project_id WHERE s.id=? AND p.id=?", stageId, projectId);
        List<Map<String, Object>> people = jdbc.queryForList("SELECT DISTINCT u.display_name,u.feishu_open_id FROM iam_user u JOIN ("
                + "SELECT owner_user_id user_id FROM collab_stage WHERE id=? UNION "
                + "SELECT reviewer_user_id FROM collab_stage WHERE id=? AND reviewer_user_id IS NOT NULL UNION "
                + "SELECT reviewer_user_id FROM collab_stage_additional_reviewer WHERE stage_id=?) assigned ON assigned.user_id=u.id ORDER BY u.display_name",
                stageId, stageId, stageId);
        String mentions = people.stream().map(person -> person.get("feishu_open_id") == null
                || String.valueOf(person.get("feishu_open_id")).isBlank()
                ? String.valueOf(person.get("display_name"))
                : "<at id=" + person.get("feishu_open_id") + "></at>")
                .collect(java.util.stream.Collectors.joining("、"));
        String projectKey = String.valueOf(row.get("project_key"));
        String stageKey = String.valueOf(row.get("stage_key"));
        String stageUrl = webBaseUrl + "/collab-projects/" + projectKey + "/stages/" + stageKey;
        String content = "项目 **" + row.get("name") + "** 的 **" + row.get("stage_title") + "** 阶段分工已更新。\n负责人及审批人："
                + mentions + "\n阶段状态：" + row.get("status") + "\n[打开阶段页面](" + stageUrl + ")";
        chats.sendInteractive(chatId, Map.of("schema", "2.0", "body", Map.of("elements", List.of(
                Map.of("tag", "markdown", "content", content)))), "collab-stage-assignment-" + outboxId);
    }

    private void stageRework(long projectId, long stageId, long outboxId) {
        String chatId = chatId(projectId);
        Map<String, Object> row = jdbc.queryForMap("SELECT p.name,p.project_key,s.stage_key,s.title stage_title,s.stage_order,s.attempt_no,s.git_required,"
                + "u.display_name,u.feishu_open_id,sub.id submission_id,ap.decision_comment,a.title artifact_title,pv.url artifact_url "
                + "FROM collab_stage s JOIN collab_project p ON p.id=s.project_id JOIN iam_user u ON u.id=s.owner_user_id "
                + "JOIN collab_submission sub ON sub.stage_id=s.id AND sub.id=(SELECT MAX(latest.id) FROM collab_submission latest WHERE latest.stage_id=s.id) "
                + "JOIN collab_approval ap ON ap.submission_id=sub.id JOIN collab_artifact_version v ON v.id=sub.artifact_version_id "
                + "JOIN collab_artifact a ON a.id=v.artifact_id LEFT JOIN collab_feishu_publication pv ON pv.artifact_version_id=v.id "
                + "WHERE s.id=? AND p.id=? AND s.status='REWORK' AND sub.status='REJECTED'", stageId, projectId);
        Object openIdValue = row.get("feishu_open_id");
        String openId = openIdValue == null ? "" : openIdValue.toString().trim();
        if (openId.isEmpty()) throw new IllegalStateException("Stage owner has no Feishu open ID");

        String projectKey = String.valueOf(row.get("project_key"));
        String stageKey = String.valueOf(row.get("stage_key"));
        String stageTitle = String.valueOf(row.get("stage_title"));
        int attemptNo = ((Number) row.get("attempt_no")).intValue();
        String stageUrl = webBaseUrl + "/collab-projects/" + projectKey + "/stages/" + stageKey;
        String prompt = CollabStagePromptFactory.submit(projectKey, stageTitle, stageId, attemptNo,
                Boolean.TRUE.equals(row.get("git_required")));
        String comment = row.get("decision_comment") == null ? "（未填写）" : String.valueOf(row.get("decision_comment"));
        List<Object> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", "项目 **" + row.get("name") + "** 的 **" + stageTitle
                + "** 阶段已驳回，请 <at id=" + openId + "></at> 修改后重新提交（第 " + attemptNo + " 轮）。"));
        elements.add(Map.of("tag", "markdown", "content", "**驳回意见：**\n" + comment));
        if (row.get("artifact_url") != null)
            elements.add(Map.of("tag", "markdown", "content", "[查看被驳回的产物](" + row.get("artifact_url") + ")"));
        elements.add(Map.of("tag", "markdown", "content", promptMarkdown("阶段完成后推送提示词", prompt)
                + "\n请先把 {local file} 占位符替换为产物在本机上的实际文件路径。"));
        if (callbacksEnabled) elements.add(promptButton(projectKey, stageId, attemptNo, "SUBMIT"));
        elements.add(Map.of("tag", "markdown", "content", "[打开阶段页面](" + stageUrl + ")"));
        chats.sendInteractive(chatId, Map.of("schema", "2.0", "body", Map.of("elements", elements)),
                "collab-stage-rework-" + outboxId);
    }

    private Map<String, Object> promptButton(String projectKey, long stageId, int attemptNo, String promptType) {
        return Map.of("tag", "button", "type", "default", "element_id", "collab_prompt_send_" + promptType.toLowerCase(),
                "name", "collab_send_prompt|" + projectKey + "|" + stageId + "|" + attemptNo + "|" + promptType,
                "text", Map.of("tag", "plain_text", "content", "单独发送"),
                "behaviors", List.of(Map.of("type", "callback", "value", Map.of("action", "collab_send_prompt",
                        "projectKey", projectKey, "stageId", stageId, "attemptNo", attemptNo, "promptType", promptType))));
    }

    private String promptMarkdown(String title, String prompt) {
        String fence = String.valueOf((char) 96).repeat(3);
        return "**" + title + "**（可直接复制）\n" + fence + "text\n" + prompt + "\n" + fence;
    }

    private void publish(long projectId, long artifactVersionId) {
        Map<String, Object> binding = binding(projectId);
        Number actor = (Number) binding.get("publish_actor_user_id");
        String space = (String) binding.get("wiki_space_id");
        String parent = (String) binding.get("wiki_parent_node_token");
        if (actor == null || space == null || parent == null)
            throw new IllegalStateException("Feishu Wiki publication target is not configured");
        Map<String, Object> artifact = jdbc.queryForMap("SELECT a.title,v.markdown_content,v.version_no,v.stage_id,s.stage_order,s.title stage_title FROM collab_artifact_version v "
                + "JOIN collab_artifact a ON a.id=v.artifact_id JOIN collab_stage s ON s.id=v.stage_id "
                + "WHERE v.id=? AND a.project_id=?", artifactVersionId, projectId);
        Map<String, Object> current = jdbc.queryForMap("SELECT document_token,node_token,url FROM collab_feishu_publication WHERE artifact_version_id=?", artifactVersionId);
        String documentToken = (String) current.get("document_token");
        String nodeToken = (String) current.get("node_token");
        String documentUrl = (String) current.get("url");
        boolean created = false;
        if (documentToken == null || documentToken.isBlank()) {
            Map<String, Object> existing = jdbc.query("SELECT pv.document_token,COALESCE(pv.node_token,'') node_token,COALESCE(pv.url,'') url FROM collab_feishu_publication pv "
                    + "JOIN collab_artifact_version v ON v.id=pv.artifact_version_id WHERE v.stage_id=? AND v.id<>? "
                    + "AND pv.document_token IS NOT NULL ORDER BY v.version_no DESC LIMIT 1",
                    rs -> rs.next() ? Map.of("document_token", rs.getString("document_token"),
                            "node_token", rs.getString("node_token"), "url", rs.getString("url")) : null,
                    artifact.get("stage_id"), artifactVersionId);
            if (existing == null) {
                jdbc.update("UPDATE collab_feishu_publication SET publish_status='CREATING',time_updated=NOW(3) WHERE artifact_version_id=?", artifactVersionId);
                JsonNode node = documents.createWikiDocx(actor.longValue(), space, parent,
                        String.format("%02d_%s", ((Number) artifact.get("stage_order")).intValue(), artifact.get("stage_title")));
                documentToken = node.path("documentToken").asText();
                nodeToken = node.path("nodeToken").asText();
                documentUrl = node.path("documentUrl").asText();
                created = true;
            } else {
                documentToken = (String) existing.get("document_token");
                nodeToken = (String) existing.get("node_token");
                documentUrl = (String) existing.get("url");
            }
        }
        jdbc.update("UPDATE collab_feishu_publication SET document_token=?,node_token=?,url=?,publish_status='WRITING',time_updated=NOW(3) WHERE artifact_version_id=?",
                documentToken, nodeToken, documentUrl, artifactVersionId);
        if (created) documents.writeNativeDocx(actor.longValue(), documentToken, (String) artifact.get("markdown_content"));
        else documents.replaceNativeDocx(actor.longValue(), documentToken, (String) artifact.get("markdown_content"));
        jdbc.update("UPDATE collab_feishu_publication SET publish_status='PUBLISHED',published_at=NOW(3),last_error=NULL,time_updated=NOW(3) WHERE artifact_version_id=?", artifactVersionId);
    }

    private void createWikiFolder(long projectId) {
        Map<String, Object> binding = jdbc.queryForMap("SELECT wiki_space_id,wiki_root_node_token,wiki_parent_node_token,publish_actor_user_id "
                + "FROM collab_feishu_binding WHERE project_id=?", projectId);
        if (binding.get("wiki_parent_node_token") != null) return;
        Number actor = (Number) binding.get("publish_actor_user_id");
        String space = (String) binding.get("wiki_space_id");
        String root = (String) binding.get("wiki_root_node_token");
        if (actor == null || space == null || root == null) throw new IllegalStateException("Wiki root is not configured");
        String name = jdbc.queryForObject("SELECT name FROM collab_project WHERE id=?", String.class, projectId);
        JsonNode node = documents.createWikiDocx(actor.longValue(), space, root, name);
        jdbc.update("UPDATE collab_feishu_binding SET wiki_parent_node_token=?,time_updated=NOW(3) WHERE project_id=?",
                node.path("nodeToken").asText(), projectId);
    }

    private Map<String, Object> binding(long projectId) {
        return jdbc.queryForMap("SELECT chat_id,wiki_space_id,wiki_parent_node_token,publish_actor_user_id FROM collab_feishu_binding WHERE project_id=?", projectId);
    }

    private boolean publicationMayExist(long artifactVersionId) {
        String status = jdbc.queryForObject("SELECT publish_status FROM collab_feishu_publication WHERE artifact_version_id=?", String.class, artifactVersionId);
        return "CREATING".equals(status) || "WRITING".equals(status);
    }

    private String chatId(long projectId) {
        String id = (String) binding(projectId).get("chat_id");
        if (id == null || id.isBlank()) throw new IllegalStateException("Project chat is not ready");
        return id;
    }

    private String shortError(Exception e) {
        String value = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        return value.substring(0, Math.min(1900, value.length()));
    }
}

package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only, submission-bound evidence collection for approval feedback. */
@Service
public class CollabApprovalFeedbackService {
    private static final Set<String> SUPPORTED_STAGES = Set.of("REQUIREMENT", "PRODUCT", "ARCHITECTURE_DESIGN",
            "UI_DESIGN", "FRONTEND_CODING", "BACKEND_CODING", "TESTING", "ACCEPTANCE");
    private final JdbcTemplate jdbc;
    private final CollabProjectAuthorizationService authorization;
    private final FeishuApprovalFeedbackGateway feishu;
    private final ObjectMapper json;

    public CollabApprovalFeedbackService(JdbcTemplate jdbc, CollabProjectAuthorizationService authorization,
            FeishuApprovalFeedbackGateway feishu, ObjectMapper json) {
        this.jdbc = jdbc; this.authorization = authorization; this.feishu = feishu; this.json = json;
    }

    public Map<String,Object> context(String requirementKey, String stageKey, Long actorId) {
        Round round = resolve(requirementKey, stageKey, null, actorId);
        Instant cutoff = Instant.now();
        List<Map<String,Object>> docs = documents(round, false);
        Map<String,Object> completeness = new LinkedHashMap<>();
        completeness.put("messagesComplete", false); completeness.put("threadsComplete", false);
        completeness.put("commentsComplete", false); completeness.put("artifactsComplete", true);
        completeness.put("decisionsComplete", true); completeness.put("attachmentCommentsSupported", false);
        return map("requirementKey", requirementKey, "stageKey", round.stageKey, "stageTitle", round.stageTitle,
                "stageStatus", round.stageStatus, "submissionId", round.submissionId, "attemptNo", round.attemptNo,
                "submissionStatus", round.submissionStatus, "artifactVersionId", round.artifactVersionId,
                "approvalStartedAt", round.approvalStartedAt, "cutoffAt", cutoff,
                "chatAvailable", round.chatId != null, "artifactCount", docs.size(),
                "dataCompleteness", completeness,
                "warnings", round.chatId == null ? List.of("FEISHU_CHAT_NOT_BOUND") : List.of());
    }

    public Map<String,Object> artifacts(String requirementKey, Long submissionId, Long actorId) {
        Round round = resolve(requirementKey, null, submissionId, actorId);
        List<Map<String,Object>> docs = documents(round, false);
        List<Map<String,Object>> supplements = supplements(round);
        return map("submissionId", round.submissionId, "attemptNo", round.attemptNo,
                "artifactVersionId", round.artifactVersionId, "items", docs,
                "supplements", supplements, "complete", true,
                "attachmentCommentsSupported", false);
    }

    public Map<String,Object> decisions(String requirementKey, Long submissionId, Long actorId) {
        Round round = resolve(requirementKey, null, submissionId, actorId);
        List<Map<String,Object>> approvals = jdbc.queryForList("select a.id approvalId,a.reviewer_user_id reviewerUserId,u.display_name reviewerName,a.state,a.decision_comment decisionComment,a.time_created createdAt,a.decided_at decidedAt " +
                "from collab_approval a join iam_user u on u.id=a.reviewer_user_id where a.submission_id=? order by a.id", round.submissionId);
        List<Map<String,Object>> roles = jdbc.queryForList("select r.requirement_role_id roleId,r.role_key_snapshot roleKey,r.role_name_snapshot roleName," +
                "sum(case when a.state='APPROVED' then 1 else 0 end) approvedCount," +
                "sum(case when a.state='PENDING' then 1 else 0 end) pendingCount," +
                "sum(case when a.state='REJECTED' then 1 else 0 end) rejectedCount," +
                "count(distinct x.reviewer_user_id) reviewerCount," +
                "group_concat(distinct u.display_name order by u.display_name separator ', ') assignedReviewers " +
                "from collab_submission_reviewer_role r left join collab_submission_reviewer_assignment x on x.submission_id=r.submission_id and x.requirement_role_id=r.requirement_role_id " +
                "left join iam_user u on u.id=x.reviewer_user_id " +
                "left join collab_approval a on a.id=x.approval_id where r.submission_id=? group by r.requirement_role_id,r.role_key_snapshot,r.role_name_snapshot order by r.role_name_snapshot", round.submissionId);
        for (Map<String,Object> role : roles) role.put("passed", ((Number) role.getOrDefault("approvedCount", 0)).intValue() > 0);
        return map("submissionId", round.submissionId, "attemptNo", round.attemptNo,
                "submissionStatus", round.submissionStatus, "items", approvals, "roles", roles, "complete", true);
    }

    public Map<String,Object> messages(String requirementKey, Long submissionId, String cutoffAt,
            String cursor, Long actorId) {
        Round round = resolve(requirementKey, null, submissionId, actorId);
        Instant cutoff = checkedCutoff(cutoffAt, round);
        if (round.chatId == null) return map("submissionId", round.submissionId, "items", List.of(), "cursor", null,
                "hasMore", false, "messagesComplete", false, "threadsComplete", false,
                "warnings", List.of("FEISHU_CHAT_NOT_BOUND"));
        Cursor state = decodeCursor(cursor, round, cutoff, "messages");
        long start = round.approvalStartedAt.getEpochSecond(); long end = cutoff.getEpochSecond();
        FeishuApprovalFeedbackGateway.ApiResult page = feishu.listMessages(round.chatId, start, end, state.pageToken);
        if (!page.ok()) return map("submissionId", round.submissionId, "items", List.of(), "cursor", null,
                "hasMore", false, "messagesComplete", false, "threadsComplete", false,
                "warnings", List.of(feishuWarning(page, "messages")));
        JsonNode data = page.body().path("data");
        List<Map<String,Object>> items = new ArrayList<>();
        boolean threadsComplete = true;
        for (JsonNode item : data.path("items")) {
            long created = parseTime(item.path("create_time").asText());
            if (created < start || created > end) continue;
            items.add(normalizeMessage(item, "chat"));
            String threadId = item.path("thread_id").asText("");
            if (!threadId.isBlank() && item.path("root_id").asText("").isBlank()) {
                FeishuApprovalFeedbackGateway.ApiResult thread = feishu.listThread(round.chatId, threadId, null);
                if (!thread.ok()) { threadsComplete = false; continue; }
                for (JsonNode reply : thread.body().path("data").path("items")) {
                    long replyTime = parseTime(reply.path("create_time").asText());
                    if (replyTime >= start && replyTime <= end) items.add(normalizeMessage(reply, "thread"));
                }
                if (thread.body().path("data").path("has_more").asBoolean(false)) threadsComplete = false;
            }
        }
        items.sort(Comparator.comparing(x -> String.valueOf(x.getOrDefault("createTime", ""))));
        boolean hasMore = data.path("has_more").asBoolean(false);
        String next = hasMore ? encodeCursor(round, cutoff, "messages", data.path("page_token").asText(""), 0) : null;
        Map<String,Map<String,Object>> deduped = new LinkedHashMap<>();
        for (Map<String,Object> item : items) deduped.putIfAbsent(String.valueOf(item.get("messageId")), item);
        List<String> warnings = threadsComplete ? List.of() : List.of("FEISHU_THREAD_HISTORY_INCOMPLETE");
        return map("submissionId", round.submissionId, "items", new ArrayList<>(deduped.values()), "cursor", next, "hasMore", hasMore,
                "messagesComplete", !hasMore, "threadsComplete", threadsComplete,
                "warnings", warnings);
    }

    public Map<String,Object> comments(String requirementKey, Long submissionId, String cutoffAt,
            String cursor, Long actorId) {
        Round round = resolve(requirementKey, null, submissionId, actorId);
        Instant cutoff = checkedCutoff(cutoffAt, round);
        List<Map<String,Object>> docs = documents(round, true).stream().filter(d -> Boolean.TRUE.equals(d.get("commentsSupported"))).toList();
        Cursor state = decodeCursor(cursor, round, cutoff, "comments");
        int docIndex = state.docIndex;
        if (docIndex < 0 || docIndex > docs.size())
            throw error("APPROVAL_FEEDBACK_CURSOR_INVALID", "Pagination cursor is invalid", HttpStatus.BAD_REQUEST);
        String pageToken = state.pageToken;
        List<Map<String,Object>> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean hasMore = false;
        boolean repliesComplete = true;
        int fetchedPages = 0;
        while (docIndex < docs.size() && fetchedPages < 5) {
            Map<String,Object> doc = docs.get(docIndex);
            FeishuApprovalFeedbackGateway.ApiResult result = feishu.listComments(actorId,
                    String.valueOf(doc.get("_documentToken")), pageToken);
            fetchedPages++;
            if (!result.ok()) {
                warnings.add(feishuWarning(result, "comments"));
                return map("submissionId", round.submissionId, "items", items, "cursor", null, "hasMore", false,
                        "commentsComplete", false, "warnings", warnings);
            }
            JsonNode data = result.body().path("data");
            JsonNode comments = data.path("items").isArray() ? data.path("items") : data.path("comments");
            for (JsonNode comment : comments) {
                long createdAt = parseTime(comment.path("create_time").asText());
                if (createdAt > cutoff.getEpochSecond()) continue;
                Map<String,Object> normalized = normalizeComment(comment, doc, round.approvalStartedAt, cutoff);
                if (!Boolean.TRUE.equals(normalized.get("repliesComplete"))) repliesComplete = false;
                items.add(normalized);
            }
            hasMore = data.path("has_more").asBoolean(false);
            if (hasMore) {
                String next = encodeCursor(round, cutoff, "comments", data.path("page_token").asText(""), docIndex);
                return map("submissionId", round.submissionId, "items", items, "cursor", next, "hasMore", true,
                        "commentsComplete", false, "repliesComplete", repliesComplete, "warnings", warnings);
            }
            pageToken = null; docIndex++;
        }
        if (docIndex < docs.size()) {
            String next = encodeCursor(round, cutoff, "comments", null, docIndex);
            return map("submissionId", round.submissionId, "items", items, "cursor", next, "hasMore", true,
                    "commentsComplete", false, "repliesComplete", repliesComplete, "warnings", warnings);
        }
        return map("submissionId", round.submissionId, "items", items, "cursor", null, "hasMore", false,
                "commentsComplete", repliesComplete, "repliesComplete", repliesComplete,
                "warnings", repliesComplete ? warnings : append(warnings, "FEISHU_COMMENT_REPLIES_INCOMPLETE"));
    }

    private Round resolve(String requirementKey, String stageKey, Long expectedSubmission, Long actorId) {
        if (stageKey != null && !SUPPORTED_STAGES.contains(stageKey.toUpperCase(Locale.ROOT)))
            throw error("APPROVAL_FEEDBACK_STAGE_UNSUPPORTED", "Unsupported stage key", HttpStatus.BAD_REQUEST);
        var project = authorization.requireMemberOrThrow(requirementKey, actorId);
        String stageFilter = stageKey == null ? "" : " and s.stage_key=?";
        String submissionFilter = expectedSubmission == null ? "" : " and sub.id=?";
        List<Object> params = new ArrayList<>();
        params.add(project.projectId());
        if (stageKey != null) params.add(stageKey.toUpperCase(Locale.ROOT));
        if (expectedSubmission != null) params.add(expectedSubmission);
        List<Map<String,Object>> rows = jdbc.queryForList("select s.id stageId,s.stage_key stageKey,s.title stageTitle,s.status stageStatus,s.attempt_no currentAttempt, " +
                "sub.id submissionId,sub.attempt_no attemptNo,sub.artifact_version_id artifactVersionId,sub.status submissionStatus, " +
                "b.chat_id chatId, " +
                "coalesce((select e.created_at from collab_stage_event e where e.stage_id=s.id and e.event_type='FEISHU_PUBLISHED' and e.reference_id=sub.id order by e.created_at desc limit 1), " +
                "(select min(a.time_created) from collab_approval a where a.submission_id=sub.id)) approvalStartedAt " +
                "from collab_stage s join collab_submission sub on sub.stage_id=s.id " +
                "left join collab_feishu_binding b on b.project_id=s.project_id " +
                "where s.project_id=?" + stageFilter + submissionFilter +
                " and ((s.status='PENDING_APPROVAL' and sub.status='PENDING_APPROVAL') or (s.status='REWORK' and sub.status='REJECTED')) " +
                "order by sub.attempt_no desc limit 1", params.toArray());
        if (rows.isEmpty()) throw error("APPROVAL_FEEDBACK_ROUND_UNAVAILABLE", "No pending approval or rejected round is available for this stage", HttpStatus.CONFLICT);
        Map<String,Object> row = rows.get(0);
        Long sid = ((Number) row.get("submissionId")).longValue();
        if (expectedSubmission != null && !expectedSubmission.equals(sid)) throw error("APPROVAL_FEEDBACK_SUBMISSION_MISMATCH", "Submission does not match the current feedback round", HttpStatus.CONFLICT);
        List<Map<String,Object>> ownerRows = jdbc.queryForList("select user_id from collab_requirement_stage_actor " +
                "where stage_id=? and duty='OWNER' and user_id=? and frozen_at is not null", row.get("stageId"), actorId);
        if (ownerRows.isEmpty()) throw error("APPROVAL_FEEDBACK_OWNER_REQUIRED", "A frozen owner of this stage is required", HttpStatus.FORBIDDEN);
        Object started = row.get("approvalStartedAt");
        if (started == null) throw error("APPROVAL_FEEDBACK_WINDOW_UNAVAILABLE", "Approval start time is unavailable", HttpStatus.CONFLICT);
        Instant start = started instanceof Timestamp t ? t.toInstant() : ((java.time.LocalDateTime) started).toInstant(java.time.ZoneOffset.UTC);
        return new Round(project.projectId(), project.projectKey(), ((Number) row.get("stageId")).longValue(),
                String.valueOf(row.get("stageKey")), String.valueOf(row.get("stageTitle")), String.valueOf(row.get("stageStatus")),
                sid, ((Number) row.get("attemptNo")).intValue(), ((Number) row.get("artifactVersionId")).longValue(),
                String.valueOf(row.get("submissionStatus")), row.get("chatId") == null ? null : String.valueOf(row.get("chatId")), start);
    }

    private List<Map<String,Object>> documents(Round round, boolean keepDocumentTokens) {
        List<Map<String,Object>> result = new ArrayList<>();
        jdbc.query("select p.document_token documentToken,p.node_token nodeToken,p.url wikiUrl,'INDEX' deliveryMode,a.title title,av.version_no versionNo " +
                "from collab_feishu_publication p join collab_artifact_version av on av.id=p.artifact_version_id join collab_artifact a on a.id=av.artifact_id " +
                "where p.artifact_version_id=? and p.publish_status='PUBLISHED'", rs -> {
            Map<String,Object> item = map("kind", "INDEX", "title", rs.getString("title"), "versionNo", rs.getInt("versionNo"),
                    "deliveryMode", "DOCX", "sourcePath", null, "wikiUrl", rs.getString("wikiUrl"),
                    "commentsSupported", rs.getString("documentToken") != null);
            item.put("_documentToken", rs.getString("documentToken")); result.add(item);
        }, round.artifactVersionId);
        jdbc.query("select f.source_path sourcePath,f.delivery_mode deliveryMode,f.feishu_target_id documentToken,f.feishu_url wikiUrl," +
                "f.source_git_url sourceGitUrl,f.publish_status publishStatus,f.media_type mediaType,p.source_commit_sha commitSha " +
                "from collab_asdm_package p join collab_asdm_package_file f on f.package_id=p.id where p.submission_id=? order by f.source_path", rs -> {
            String mode = rs.getString("deliveryMode"); String docToken = rs.getString("documentToken");
            boolean docx = "DOCX".equalsIgnoreCase(mode) && docToken != null;
            Map<String,Object> item = map("kind", "ASDM_FILE", "title", rs.getString("sourcePath"), "sourcePath", rs.getString("sourcePath"),
                    "deliveryMode", mode, "wikiUrl", rs.getString("wikiUrl"), "sourceGitUrl", rs.getString("sourceGitUrl"),
                    "publishStatus", rs.getString("publishStatus"), "mediaType", rs.getString("mediaType"), "commitSha", rs.getString("commitSha"),
                    "commentsSupported", docx);
            if (docx) item.put("_documentToken", docToken);
            result.add(item);
        }, round.submissionId);
        if (!keepDocumentTokens) for (Map<String,Object> item : result) item.remove("_documentToken");
        return result;
    }

    private List<Map<String,Object>> supplements(Round round) {
        return jdbc.query("select f.source_path sourcePath,f.delivery_mode deliveryMode,f.feishu_url wikiUrl,f.source_git_url sourceGitUrl," +
                "f.publish_status publishStatus,f.media_type mediaType,f.time_created publishedAt,j.head_sha commitSha " +
                "from collab_asdm_sync_stage ss join collab_asdm_sync_job j on j.id=ss.job_id " +
                "join collab_asdm_sync_file f on f.sync_stage_id=ss.id " +
                "where ss.stage_id=? and f.time_created >= ? " +
                "order by f.time_created,f.source_path", (rs, n) -> map("kind", "SUPPLEMENTAL", "classification", "AFTER_APPROVAL_BASELINE",
                        "title", rs.getString("sourcePath"), "sourcePath", rs.getString("sourcePath"), "deliveryMode", rs.getString("deliveryMode"),
                        "wikiUrl", rs.getString("wikiUrl"), "sourceGitUrl", rs.getString("sourceGitUrl"),
                        "publishStatus", rs.getString("publishStatus"), "mediaType", rs.getString("mediaType"),
                        "publishedAt", rs.getTimestamp("publishedAt") == null ? null : rs.getTimestamp("publishedAt").toInstant(),
                        "commitSha", rs.getString("commitSha"), "commentsSupported", false), round.stageId, Timestamp.from(round.approvalStartedAt));
    }

    private Map<String,Object> normalizeMessage(JsonNode n, String source) {
        String content = n.path("body").path("content").asText(n.path("content").asText(""));
        String text = content;
        try { JsonNode parsed = json.readTree(content); text = textContent(parsed); } catch (Exception ignored) {}
        return map("messageId", n.path("message_id").asText(""), "rootId", n.path("root_id").asText(""),
                "parentId", n.path("parent_id").asText(""), "threadId", n.path("thread_id").asText(""),
                "senderId", n.path("sender").path("id").asText(""), "senderType", n.path("sender").path("sender_type").asText(""),
                "messageType", n.path("msg_type").asText(""), "text", text, "createTime", n.path("create_time").asText(""),
                "updateTime", n.path("update_time").asText(""), "source", source,
                "mentions", n.path("mentions").isArray() ? json.convertValue(n.path("mentions"), List.class) : List.of(),
                "unsupportedPayload", !Set.of("text", "post", "interactive").contains(n.path("msg_type").asText("")));
    }

    private Map<String,Object> normalizeComment(JsonNode comment, Map<String,Object> doc, Instant start, Instant cutoff) {
        List<Map<String,Object>> replies = new ArrayList<>();
        JsonNode rep = comment.path("reply_list").path("replies");
        for (int i=0;i<rep.size();i++) {
            JsonNode r = rep.get(i);
            if (parseTime(r.path("create_time").asText()) > cutoff.getEpochSecond()) continue;
            replies.add(map("replyId", r.path("reply_id").asText(""), "userId", r.path("user_id").asText(""),
                    "text", textContent(r.path("content")), "createTime", r.path("create_time").asText(""), "updateTime", r.path("update_time").asText(""), "isOriginalComment", i==0));
        }
        long created = parseTime(comment.path("create_time").asText()); long updated = parseTime(comment.path("update_time").asText());
        String window = created > cutoff.getEpochSecond() || updated > cutoff.getEpochSecond() ? "after_cutoff_excluded" : updated < start.getEpochSecond() ? "before_window" : "in_window";
        return map("documentTitle", doc.get("title"), "wikiUrl", doc.get("wikiUrl"), "sourcePath", doc.get("sourcePath"),
                "commentId", comment.path("comment_id").asText(""), "isWhole", comment.path("is_whole").asBoolean(true),
                "quote", comment.path("quote").asText(""), "isSolved", comment.path("is_solved").asBoolean(false),
                "userId", comment.path("user_id").asText(""), "createTime", comment.path("create_time").asText(""),
                "updateTime", comment.path("update_time").asText(""), "window", window, "replies", replies,
                "repliesComplete", !comment.path("reply_list").path("has_more").asBoolean(false));
    }

    private String textContent(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isObject()) {
            if (node.path("text_run").has("text")) return node.path("text_run").path("text").asText();
            StringBuilder b = new StringBuilder(); node.fields().forEachRemaining(e -> b.append(textContent(e.getValue()))); return b.toString();
        }
        if (node.isArray()) { StringBuilder b = new StringBuilder(); node.forEach(x -> b.append(textContent(x))); return b.toString(); }
        return "";
    }

    private Instant checkedCutoff(String raw, Round round) {
        if (raw == null || raw.isBlank()) throw error("APPROVAL_FEEDBACK_CUTOFF_REQUIRED", "cutoffAt from context is required", HttpStatus.BAD_REQUEST);
        try { Instant cutoff = Instant.parse(raw); if (cutoff.isBefore(round.approvalStartedAt) || cutoff.isAfter(Instant.now().plusSeconds(5))) throw new IllegalArgumentException(); return cutoff; }
        catch (Exception e) { throw error("APPROVAL_FEEDBACK_CUTOFF_INVALID", "cutoffAt is invalid", HttpStatus.BAD_REQUEST); }
    }

    private Cursor decodeCursor(String encoded, Round round, Instant cutoff, String type) {
        if (encoded == null || encoded.isBlank()) return new Cursor(null, 0);
        try {
            JsonNode node = json.readTree(Base64.getUrlDecoder().decode(encoded));
            if (!type.equals(node.path("type").asText()) || round.submissionId != node.path("submissionId").asLong() || !cutoff.toString().equals(node.path("cutoffAt").asText())) throw new IllegalArgumentException();
            return new Cursor(node.path("pageToken").isMissingNode() || node.path("pageToken").isNull() ? null : node.path("pageToken").asText(), node.path("docIndex").asInt(0));
        } catch (Exception e) { throw error("APPROVAL_FEEDBACK_CURSOR_INVALID", "Pagination cursor is invalid or belongs to another request", HttpStatus.BAD_REQUEST); }
    }
    private String encodeCursor(Round round, Instant cutoff, String type, String pageToken, int docIndex) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(map("type",type,"submissionId",round.submissionId,"cutoffAt",cutoff.toString(),"pageToken",pageToken,"docIndex",docIndex))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static long parseTime(String value) {
        try { long parsed = Long.parseLong(value); return parsed > 100_000_000_000L ? parsed / 1000L : parsed; }
        catch(Exception e) { return 0; }
    }
    private String feishuWarning(FeishuApprovalFeedbackGateway.ApiResult r, String source) {
        if (r.code() == 230027) return "messages".equals(source)
                ? "FEISHU_PERMISSION_MISSING_IM_MESSAGE_GROUP_MSG" : "FEISHU_DOCUMENT_COMMENT_PERMISSION_MISSING";
        return "FEISHU_" + source.toUpperCase(Locale.ROOT) + "_READ_FAILED:" + r.code();
    }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),pairs[i+1]); return m; }
    private static List<String> append(List<String> list, String item) { List<String> copy=new ArrayList<>(list); copy.add(item); return copy; }
    private BusinessException error(String code,String message,HttpStatus status) { return new BusinessException(code,message,status); }
    private record Cursor(String pageToken,int docIndex) {}
    private record Round(long projectId,String projectKey,long stageId,String stageKey,String stageTitle,String stageStatus,
            long submissionId,int attemptNo,long artifactVersionId,String submissionStatus,String chatId,Instant approvalStartedAt) {}
}

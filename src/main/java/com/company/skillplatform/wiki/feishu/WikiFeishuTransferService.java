package com.company.skillplatform.wiki.feishu;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.auth.application.AuthService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.domain.WikiKnowledgeCategory;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WikiFeishuTransferService {
    private static final Logger log = LoggerFactory.getLogger(WikiFeishuTransferService.class);
    private static final Pattern DOC_URL = Pattern.compile("^https://[A-Za-z0-9.-]+\\.feishu\\.cn/(docx|docs|wiki)/([A-Za-z0-9_-]+)(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final FeishuDriveGateway drive;
    private final FeishuDocxMarkdownConverter converter;
    private final WikiDocumentService wiki;
    private final FeishuDocumentMcpProxy docx;
    private final FeishuMarkdownDocxWriter docxWriter;
    private final AuthService auth;
    private final TransactionTemplate transactions;
    private final NotificationService notifications;
    private final AuditService audit;
    private final IamUserRepository users;
    private final EntityManager entityManager;

    public WikiFeishuTransferService(JdbcTemplate db, ObjectMapper json, FeishuDriveGateway drive,
            FeishuDocxMarkdownConverter converter, WikiDocumentService wiki, FeishuDocumentMcpProxy docx,
            FeishuMarkdownDocxWriter docxWriter,
            AuthService auth, TransactionTemplate transactions, NotificationService notifications,
            AuditService audit, IamUserRepository users, EntityManager entityManager) {
        this.db = db; this.json = json; this.drive = drive; this.converter = converter;
        this.wiki = wiki; this.docx = docx; this.docxWriter = docxWriter;
        this.auth = auth; this.transactions = transactions; this.notifications = notifications;
        this.audit = audit; this.users = users; this.entityManager = entityManager;
    }

    public record Source(String fileType, String fileToken) {}
    public record Target(String operation, Long teamId, Long parentWikiId, String knowledgeCategory, List<Long> skillIds,
                         Long wikiDocumentId, Integer expectedWikiVersionNo, String contentMode) {}
    public record ImportRequest(Source source, Target target, String idempotencyKey) {}
    public record ExportRequest(String format, String folderToken, Integer sourceRevisionNo, String sourceAudience, String idempotencyKey) {}
    public record JobAccepted(long jobId, String status) {}
    public record Preview(String title, String type, String excerpt, List<String> warnings) {}
    public record EditableContent(String title, String type, String markdownContent, List<String> warnings) {}

    public FeishuDriveGateway.Access access(long actor) { return drive.access(actor); }
    public String root(long actor) { return drive.root(actor); }
    public FeishuDriveGateway.DrivePage files(long actor, String folder, String page) { return drive.list(actor, folder, page); }
    public com.fasterxml.jackson.databind.JsonNode search(long actor, String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 100) throw bad("FEISHU_SEARCH_INVALID", "请输入 1 至 100 个字符", HttpStatus.BAD_REQUEST);
        return docx.search(actor, keyword.trim(), 30);
    }
    public FeishuDriveGateway.Resolved resolveUrl(long actor, String url) {
        Matcher match = DOC_URL.matcher(url == null ? "" : url.trim());
        if (!match.matches()) throw bad("FEISHU_DOCUMENT_URL_INVALID", "仅支持飞书 HTTPS 文档链接", HttpStatus.BAD_REQUEST);
        String type = "docs".equalsIgnoreCase(match.group(1)) ? "DOC" : match.group(1).toUpperCase();
        return drive.resolve(actor, type, match.group(2));
    }
    public Preview preview(long actor, Source source) {
        validateSource(source);
        var resolved = drive.resolve(actor, source.fileType(), source.fileToken());
        String content = drive.read(actor, source.fileType(), source.fileToken(), converter);
        return new Preview(resolved.title(), resolved.type(), content.substring(0, Math.min(1500, content.length())), List.of());
    }

    public EditableContent editableContent(long actor, Source source) {
        validateSource(source);
        var resolved = drive.resolve(actor, source.fileType(), source.fileToken());
        String markdown = drive.read(actor, source.fileType(), source.fileToken(), converter);
        if (markdown.isBlank() || markdown.length() > 1_000_000)
            throw bad("FEISHU_DOCUMENT_CONVERT_FAILED", "飞书正文为空或超过 Wiki 上限", HttpStatus.UNPROCESSABLE_ENTITY);
        return new EditableContent(resolved.title(), resolved.type(), markdown, List.of());
    }

    public JobAccepted startImport(long actor, ImportRequest request) {
        if (request == null || request.target() == null) throw bad("WIKI_FEISHU_REQUEST_INVALID", "导入参数不完整", HttpStatus.BAD_REQUEST);
        validateKey(request.idempotencyKey()); validateSource(request.source());
        Target target = request.target();
        if (!"CREATE".equals(target.operation()) && !"UPDATE".equals(target.operation())) throw bad("WIKI_FEISHU_REQUEST_INVALID", "导入操作无效", HttpStatus.BAD_REQUEST);
        if (!drive.access(actor).authorized()) throw bad("FEISHU_AUTH_REQUIRED", "请重新授权飞书文档访问", HttpStatus.FORBIDDEN);
        var principal = auth.currentUser(actor);
        if ("CREATE".equals(target.operation())) {
            if (!principal.permissions().contains("wiki:create") && !principal.permissions().contains("admin:identity")) throw bad("WIKI_PERMISSION_REQUIRED", "没有 Wiki 创建权限", HttpStatus.FORBIDDEN);
            if (target.teamId() == null && !principal.permissions().contains("wiki:publish:direct") && !principal.permissions().contains("admin:identity"))
                throw bad("WIKI_TEAM_MEMBER_REQUIRED", "没有全平台直接发布权限", HttpStatus.FORBIDDEN);
            if (target.teamId() != null && target.teamId() <= 0) throw bad("WIKI_TEAM_INVALID", "目标团队无效", HttpStatus.BAD_REQUEST);
            if (target.knowledgeCategory() == null) throw bad("WIKI_KNOWLEDGE_CATEGORY_INVALID", "请选择知识分类", HttpStatus.BAD_REQUEST);
            WikiKnowledgeCategory.parse(target.knowledgeCategory(), null);
        } else {
            if (target.wikiDocumentId() == null || target.expectedWikiVersionNo() == null || !List.of("REPLACE", "APPEND").contains(target.contentMode()))
                throw bad("WIKI_FEISHU_REQUEST_INVALID", "更新参数不完整", HttpStatus.BAD_REQUEST);
            var current = wiki.get(target.wikiDocumentId(), actor);
            if (!current.canEdit()) throw bad("WIKI_EDIT_FORBIDDEN", "没有 Wiki 编辑权限", HttpStatus.FORBIDDEN);
            if (current.versionNo() != target.expectedWikiVersionNo()) throw bad("WIKI_VERSION_CONFLICT", "Wiki 已被修改，请刷新", HttpStatus.CONFLICT);
        }
        var source = drive.resolve(actor, request.source().fileType(), request.source().fileToken());
        String payload = stringify(request);
        Long existing = idempotent(actor, request.idempotencyKey(), hash(payload));
        if (existing != null) return new JobAccepted(existing, "PENDING");
        rateLimit(actor);
        long id = insert(actor, "IMPORT", target.operation(), null, request.idempotencyKey(), hash(payload),
                target.wikiDocumentId(), null, target.expectedWikiVersionNo(), source.type(), source.token(), null,
                target.contentMode(), source.title(), target.teamId(), target.parentWikiId(), target.knowledgeCategory(),
                target.skillIds() == null ? List.of() : target.skillIds(), null);
        log.info("event=wiki.feishu.import.started actorId={} jobId={} wikiId={}", actor, id, target.wikiDocumentId());
        return new JobAccepted(id, "PENDING");
    }

    public JobAccepted startExport(long actor, long wikiId, ExportRequest request) {
        if (request == null) throw bad("WIKI_FEISHU_REQUEST_INVALID", "导出参数不完整", HttpStatus.BAD_REQUEST);
        validateKey(request.idempotencyKey());
        if (!List.of("DOCX", "MARKDOWN_FILE").contains(request.format())) throw bad("WIKI_FEISHU_REQUEST_INVALID", "导出格式无效", HttpStatus.BAD_REQUEST);
        String audience = request.sourceAudience() == null ? "CURRENT" : request.sourceAudience();
        if (!List.of("CURRENT", "PLATFORM_APPROVED").contains(audience)) throw bad("WIKI_FEISHU_REQUEST_INVALID", "Wiki 版本范围无效", HttpStatus.BAD_REQUEST);
        if (request.folderToken() != null && !request.folderToken().isBlank()) FeishuDriveGateway.validToken(request.folderToken());
        var source = wiki.get(wikiId, actor, "PLATFORM_APPROVED".equals(audience));
        if (!source.canEdit()) throw bad("WIKI_EDIT_FORBIDDEN", "没有 Wiki 导出权限", HttpStatus.FORBIDDEN);
        if (request.sourceRevisionNo() == null || source.revisionNo() != request.sourceRevisionNo()) throw bad("WIKI_VERSION_CONFLICT", "Wiki 修订已变化，请刷新", HttpStatus.CONFLICT);
        if (!drive.access(actor).authorized()) throw bad("FEISHU_AUTH_REQUIRED", "请重新授权飞书文档访问", HttpStatus.FORBIDDEN);
        Long revisionId = "PLATFORM_APPROVED".equals(audience)
                ? db.queryForObject("SELECT approved_revision_id FROM wiki_platform_snapshot WHERE document_id=?", Long.class, wikiId)
                : db.queryForObject("SELECT current_revision_id FROM wiki_document WHERE id=?", Long.class, wikiId);
        if (revisionId == null) throw bad("WIKI_REVISION_REQUIRED", "Wiki 缺少修订", HttpStatus.CONFLICT);
        String payload = stringify(Map.of("wikiId", wikiId, "request", request));
        Long existing = idempotent(actor, request.idempotencyKey(), hash(payload));
        if (existing != null) return new JobAccepted(existing, "PENDING");
        rateLimit(actor);
        long id = insert(actor, "EXPORT", null, request.format(), request.idempotencyKey(), hash(payload), wikiId,
                revisionId, null, null, null, audience, null, source.title(), null, null, null, null, request.folderToken());
        log.info("event=wiki.feishu.export.started actorId={} jobId={} wikiId={} revisionId={}", actor, id, wikiId, revisionId);
        return new JobAccepted(id, "PENDING");
    }

    public Map<String, Object> job(long actor, long id) {
        var rows = db.queryForList("SELECT id,actor_user_id actorUserId,direction,operation,format,status,wiki_document_id wikiDocumentId,"
                + "feishu_output_url feishuOutputUrl,conversion_warnings_json conversionWarnings,attempt_count attemptCount,"
                + "error_code errorCode,error_message errorMessage,created_at createdAt,completed_at completedAt "
                + "FROM wiki_feishu_transfer_job WHERE id=? AND actor_user_id=?", id, actor);
        if (rows.isEmpty()) throw bad("WIKI_FEISHU_JOB_NOT_FOUND", "任务不存在", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    public JobAccepted retry(long actor, long id) {
        Map<String, Object> state = job(actor, id);
        String status = String.valueOf(state.get("status"));
        if (!List.of("FAILED", "AUTH_REQUIRED").contains(status)) throw bad("WIKI_FEISHU_JOB_RETRY_INVALID", "该任务不可重试", HttpStatus.CONFLICT);
        if (number(state.get("attemptCount")) >= 3) throw bad("WIKI_FEISHU_JOB_RETRY_LIMIT", "重试次数已达上限，请联系管理员核对任务", HttpStatus.CONFLICT);
        auth.currentUser(actor);
        if (!drive.access(actor).authorized()) throw bad("FEISHU_AUTH_REQUIRED", "请重新授权飞书文档访问", HttpStatus.FORBIDDEN);
        db.update("UPDATE wiki_feishu_transfer_job SET status='PENDING',error_code=NULL,error_message=NULL WHERE id=? AND actor_user_id=? AND status IN ('FAILED','AUTH_REQUIRED')", id, actor);
        return new JobAccepted(id, "PENDING");
    }

    @Scheduled(fixedDelayString = "${wiki.feishu.transfer.poll-ms:3000}")
    public void poll() {
        db.update("UPDATE wiki_feishu_transfer_job SET status='RECONCILE_REQUIRED',error_code='WORKER_LEASE_EXPIRED',error_message='任务执行中断，请核对远端文件后重试',completed_at=NOW(3) WHERE status='RUNNING' AND claimed_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)");
        List<Long> pending = db.query("SELECT id FROM wiki_feishu_transfer_job WHERE status='PENDING' ORDER BY id LIMIT 1", (rs, row) -> rs.getLong(1));
        if (pending.isEmpty()) return;
        long id = pending.get(0);
        if (db.update("UPDATE wiki_feishu_transfer_job SET status='RUNNING',claimed_at=NOW(3),attempt_count=attempt_count+1 WHERE id=? AND status='PENDING'", id) != 1) return;
        Map<String, Object> task = db.queryForMap("SELECT * FROM wiki_feishu_transfer_job WHERE id=?", id);
        long actor = ((Number) task.get("actor_user_id")).longValue();
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            var current = auth.currentUser(actor);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                    current.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
            SecurityContextHolder.setContext(context);
            if ("IMPORT".equals(task.get("direction"))) processImport(task, actor);
            else processExport(task, actor);
            notifySafely(actor, id, true, String.valueOf(task.get("direction")), null);
        } catch (Exception ex) {
            String code = ex instanceof BusinessException business ? business.getCode() : "WIKI_FEISHU_TRANSFER_FAILED";
            String status = "FEISHU_AUTH_REQUIRED".equals(code) || "FEISHU_DRIVE_SCOPE_REQUIRED".equals(code) ? "AUTH_REQUIRED"
                    : "WIKI_VERSION_CONFLICT".equals(code) ? "CONFLICT" : "FEISHU_REMOTE_RESULT_UNCERTAIN".equals(code) ? "RECONCILE_REQUIRED" : "FAILED";
            db.update("UPDATE wiki_feishu_transfer_job SET status=?,error_code=?,error_message=?,completed_at=NOW(3) WHERE id=?",
                    status, code, safe(ex.getMessage()), id);
            log.warn("event=wiki.feishu.transfer.failed actorId={} jobId={} code={}", actor, id, code, ex);
            notifySafely(actor, id, false, String.valueOf(task.get("direction")), code);
        } finally { SecurityContextHolder.setContext(previous); }
    }

    private void processImport(Map<String, Object> task, long actor) {
        long id = number(task.get("id"));
        String type = String.valueOf(task.get("source_type"));
        String token = String.valueOf(task.get("source_token"));
        String markdown = drive.read(actor, type, token, converter);
        if (markdown.isBlank() || markdown.length() > 1_000_000) throw bad("FEISHU_DOCUMENT_CONVERT_FAILED", "飞书正文为空或过长", HttpStatus.UNPROCESSABLE_ENTITY);
        transactions.execute(status -> {
            Long wikiId = task.get("wiki_document_id") == null ? null : number(task.get("wiki_document_id"));
            WikiDocumentService.DocumentView result;
            if ("CREATE".equals(task.get("operation"))) {
                @SuppressWarnings("unchecked") List<Long> skillIds = readSkills(String.valueOf(task.get("target_skill_ids_json")));
                result = wiki.create(new WikiDocumentService.CreateCommand(String.valueOf(task.get("target_title")),
                        task.get("target_team_id") == null ? null : number(task.get("target_team_id")), skillIds,
                        markdown, task.get("target_parent_wiki_id") == null ? null : number(task.get("target_parent_wiki_id")),
                        String.valueOf(task.get("target_knowledge_category"))), actor, "wiki-feishu-" + id);
                wikiId = result.id();
            } else {
                result = wiki.get(wikiId, actor);
                if (!result.canEdit() || result.versionNo() != ((Number) task.get("expected_wiki_version_no")).intValue())
                    throw bad("WIKI_VERSION_CONFLICT", "Wiki 已被修改，请刷新后重新导入", HttpStatus.CONFLICT);
                String content = "APPEND".equals(task.get("content_mode")) ? result.markdownContent().stripTrailing() + "\n\n" + markdown : markdown;
                result = wiki.update(wikiId, new WikiDocumentService.UpdateCommand(result.title(), content, result.versionNo(),
                        null, null, false, null), actor, "wiki-feishu-" + id);
            }
            entityManager.flush();
            Long revisionId = db.queryForObject("SELECT current_revision_id FROM wiki_document WHERE id=?", Long.class, wikiId);
            db.update("INSERT INTO wiki_feishu_document_link(wiki_document_id,source_direction,feishu_document_token,feishu_document_type,wiki_revision_id,transfer_job_id,actor_user_id) VALUES(?,'IMPORT',?,?,?,?,?)",
                    wikiId, token, type, revisionId, id, actor);
            db.update("UPDATE wiki_feishu_transfer_job SET status='SUCCEEDED',wiki_document_id=?,wiki_revision_id=?,completed_at=NOW(3) WHERE id=?",
                    wikiId, revisionId, id);
            audit.success("WIKI_FEISHU_IMPORTED", users.findById(actor).orElseThrow(), "WIKI_DOCUMENT", wikiId,
                    "wiki-feishu-" + id, null, Map.of("revisionId", revisionId), Map.of("jobId", id, "sourceType", type));
            return null;
        });
    }

    private void processExport(Map<String, Object> task, long actor) {
        long id = number(task.get("id"));
        long wikiId = number(task.get("wiki_document_id"));
        String audience = String.valueOf(task.get("source_audience"));
        var current = wiki.get(wikiId, actor, "PLATFORM_APPROVED".equals(audience));
        if (!current.canEdit()) throw bad("WIKI_EDIT_FORBIDDEN", "已失去 Wiki 导出权限", HttpStatus.FORBIDDEN);
        String markdown = db.queryForObject("SELECT markdown_content FROM wiki_document_revision WHERE id=? AND document_id=?",
                String.class, number(task.get("wiki_revision_id")), wikiId);
        if (markdown == null) throw bad("WIKI_VERSION_CONFLICT", "源修订不存在", HttpStatus.CONFLICT);
        String format = String.valueOf(task.get("format"));
        String folder = task.get("folder_token") == null ? null : String.valueOf(task.get("folder_token"));
        String token = task.get("feishu_output_token") == null ? null : String.valueOf(task.get("feishu_output_token"));
        String url;
        if ("DOCX".equals(format)) {
            docxWriter.parse(markdown);
            if (token == null) {
                token = drive.createDocx(actor, folder, String.valueOf(task.get("target_title")));
                persistRemoteToken(id, token, "https://feishu.cn/docx/" + token);
            }
            docxWriter.replace(actor, token, markdown);
            url = "https://feishu.cn/docx/" + token;
        } else {
            if (token == null) {
                String name = String.valueOf(task.get("target_title")).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + ".md";
                var uploaded = drive.uploadMarkdown(actor, folder, name, markdown.getBytes(StandardCharsets.UTF_8));
                token = uploaded.token();
                persistRemoteToken(id, token, uploaded.url());
            }
            url = "https://feishu.cn/file/" + token;
        }
        String finalToken = token, finalUrl = url;
        transactions.execute(status -> {
            db.update("INSERT INTO wiki_feishu_document_link(wiki_document_id,source_direction,feishu_document_token,feishu_document_type,feishu_document_url,wiki_revision_id,transfer_job_id,actor_user_id) VALUES(?,'EXPORT',?,?,?,?,?,?)",
                    wikiId, finalToken, format, finalUrl, number(task.get("wiki_revision_id")), id, actor);
            db.update("UPDATE wiki_feishu_transfer_job SET status='SUCCEEDED',feishu_output_url=?,completed_at=NOW(3) WHERE id=?", finalUrl, id);
            audit.success("WIKI_FEISHU_EXPORTED", users.findById(actor).orElseThrow(), "WIKI_DOCUMENT", wikiId,
                    "wiki-feishu-" + id, null, Map.of("revisionId", number(task.get("wiki_revision_id"))),
                    Map.of("jobId", id, "format", format));
            return null;
        });
    }

    private void notifySafely(long actor, long id, boolean succeeded, String direction, String errorCode) {
        try { notifications.wikiFeishuTransferCompleted(actor, id, succeeded, direction, errorCode); }
        catch (Exception ex) { log.warn("event=wiki.feishu.notification.failed actorId={} jobId={}", actor, id); }
    }

    private void rateLimit(long actor) {
        Integer recent = db.queryForObject("SELECT COUNT(*) FROM wiki_feishu_transfer_job WHERE actor_user_id=? AND created_at > DATE_SUB(NOW(3), INTERVAL 1 MINUTE)", Integer.class, actor);
        if (recent != null && recent >= 10) throw bad("WIKI_FEISHU_RATE_LIMITED", "每分钟最多创建 10 个飞书传输任务", HttpStatus.TOO_MANY_REQUESTS);
    }

    private void persistRemoteToken(long id, String token, String url) {
        try {
            if (db.update("UPDATE wiki_feishu_transfer_job SET feishu_output_token=?,feishu_output_url=? WHERE id=? AND status='RUNNING'", token, url, id) != 1)
                throw new IllegalStateException("Job state changed");
        } catch (Exception ex) { throw bad("FEISHU_REMOTE_RESULT_UNCERTAIN", "飞书文件可能已创建，请人工核对", HttpStatus.CONFLICT); }
    }

    private long insert(long actor, String direction, String operation, String format, String key, String requestHash,
            Long wikiId, Long revisionId, Integer expectedVersion, String sourceType, String sourceToken,
            String audience, String contentMode, String title, Long teamId, Long parentId, String category,
            List<Long> skillIds, String folder) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            db.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO wiki_feishu_transfer_job(actor_user_id,direction,operation,format,idempotency_key,request_hash,wiki_document_id,wiki_revision_id,expected_wiki_version_no,source_type,source_token,source_audience,content_mode,target_title,target_team_id,target_parent_wiki_id,target_knowledge_category,target_skill_ids_json,folder_token) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                Object[] values = {actor,direction,operation,format,key,requestHash,wikiId,revisionId,expectedVersion,sourceType,sourceToken,audience,contentMode,title,teamId,parentId,category,skillIds == null ? null : stringify(skillIds),folder};
                for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                return statement;
            }, keys);
            return Objects.requireNonNull(keys.getKey()).longValue();
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            Long existing = idempotent(actor, key, requestHash);
            if (existing != null) return existing;
            throw duplicate;
        }
    }

    private Long idempotent(long actor, String key, String requestHash) {
        var rows = db.queryForList("SELECT id,request_hash FROM wiki_feishu_transfer_job WHERE actor_user_id=? AND idempotency_key=?", actor, key);
        if (rows.isEmpty()) return null;
        if (!requestHash.equals(rows.get(0).get("request_hash"))) throw bad("WIKI_FEISHU_IDEMPOTENCY_CONFLICT", "幂等键已用于另一请求", HttpStatus.CONFLICT);
        return number(rows.get(0).get("id"));
    }
    private List<Long> readSkills(String raw) {
        try { return json.readValue(raw, new TypeReference<List<Long>>() {}); }
        catch (Exception ex) { throw bad("WIKI_FEISHU_REQUEST_INVALID", "任务 Skill 快照无效", HttpStatus.CONFLICT); }
    }
    private String stringify(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static void validateSource(Source source) {
        if (source == null || !List.of("DOCX", "DOC", "WIKI", "FILE").contains(source.fileType()))
            throw bad("FEISHU_DOCUMENT_TYPE_UNSUPPORTED", "飞书来源类型不支持", HttpStatus.BAD_REQUEST);
        FeishuDriveGateway.validToken(source.fileToken());
    }
    private static void validateKey(String key) {
        try { if (key == null || key.length() > 80) throw new IllegalArgumentException(); UUID.fromString(key); }
        catch (Exception ex) { throw bad("WIKI_FEISHU_REQUEST_INVALID", "幂等键必须是 UUID", HttpStatus.BAD_REQUEST); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String safe(String value) { return value == null ? "操作失败" : value.substring(0, Math.min(1000, value.length())); }
    private static BusinessException bad(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
}

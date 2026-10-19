package com.company.skillplatform.external;

import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExternalResourceService {
    private static final Logger log = LoggerFactory.getLogger(ExternalResourceService.class);
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ObjectStoragePort storage;
    private final long maxBytes;

    public ExternalResourceService(JdbcTemplate db, TransactionTemplate transactions, ObjectStoragePort storage,
            @Value("${integration.external.max-file-bytes:52428800}") long maxBytes) {
        this.db = db; this.transactions = transactions; this.storage = storage; this.maxBytes = maxBytes;
    }

    public PushResult push(PushRequest request, MultiValueMap<String, MultipartFile> files, String requestId) {
        int created = 0, updated = 0, unchanged = 0;
        List<Failure> failures = new ArrayList<>();
        List<Success> successes = new ArrayList<>();
        // Role state is committed independently from resource processing, so partial resource
        // failures (or a state-only request with resources=[]) cannot roll it back.
        persistRole(request);
        for (ResourceInput input : request.resources()) {
            String resourceId = input == null ? null : input.resourceId();
            String fileKey = input == null ? null : input.fileKey();
            try {
                Receipt receipt = receive(request, input, files == null ? null : files.getFirst(fileKey));
                switch (receipt.status()) {
                    case "CREATED" -> created++;
                    case "UPDATED" -> updated++;
                    default -> unchanged++;
                }
                successes.add(new Success(resourceId, receipt.versionId(), receipt.status()));
            } catch (ResourceFailure ex) {
                failures.add(new Failure(resourceId, fileKey, ex.code, ex.getMessage()));
            } catch (Exception ex) {
                log.error("event=external.resource.push.failed requestId={} source={} resourceId={}", requestId, request.sourceSystem(), resourceId, ex);
                failures.add(new Failure(resourceId, fileKey, "STORAGE_ERROR", "资源保存失败，请重试"));
            }
        }
        return new PushResult(request.resources().size(), created, updated, unchanged, failures.size(), failures, successes, requestId);
    }

    private Receipt receive(PushRequest request, ResourceInput input, MultipartFile file) throws Exception {
        if (input == null || blank(input.resourceId()) || blank(input.resourceName()) || blank(input.fileKey())
                || !("SKILL".equals(input.resourceType()) || "DOCUMENT".equals(input.resourceType()))
                || input.resourceId().length() > 255 || input.resourceName().length() > 512 || input.fileKey().length() > 128
                || (input.description() != null && input.description().length() > 10000)
                || (input.version() != null && input.version().length() > 128)) {
            throw new ResourceFailure("INVALID_RESOURCE", "资源字段无效");
        }
        if (file == null || file.isEmpty()) throw new ResourceFailure("FILE_MISSING", "未找到对应上传文件");
        if (file.getSize() > maxBytes) throw new ResourceFailure("FILE_TOO_LARGE", "文件超过上传大小限制");
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") || filename.length() > 512)
            throw new ResourceFailure("INVALID_FILE_TYPE", "仅支持 ZIP 文件");
        String normalizedFilename = filename.replace('\\','/');
        String safeFilename = normalizedFilename.substring(normalizedFilename.lastIndexOf('/') + 1);
        if (safeFilename.isBlank() || safeFilename.chars().anyMatch(c -> c < 0x20 || c == 0x7f))
            throw new ResourceFailure("INVALID_FILE_TYPE", "文件名无效");
        Path temp = Files.createTempFile("external-resource-", ".zip");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) { Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            long size = Files.size(temp);
            if (size < 4 || size > maxBytes) throw new ResourceFailure("FILE_TOO_LARGE", "文件大小无效");
            try (InputStream in = Files.newInputStream(temp)) {
                byte[] header = in.readNBytes(4);
                if (header[0] != 'P' || header[1] != 'K' || !((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6)))
                    throw new ResourceFailure("INVALID_FILE_TYPE", "文件不是 ZIP");
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            String objectKey = "external-resources/raw/" + UUID.randomUUID() + ".zip";
            boolean uploaded = false;
            db.update("INSERT INTO external_resource_object_lease(object_key) VALUES(?)",objectKey);
            try {
                // The original bytes remain untouched; parsing is explicitly deferred to an administrator.
                uploaded = true; // Also compensate a partially written object when put itself fails.
                try (InputStream in = Files.newInputStream(temp)) { storage.put(objectKey, in, size, "application/zip"); }
                return transactions.execute(status -> persist(request, input, safeFilename, size, sha, objectKey));
            } finally {
                // An unchanged or rejected upload never becomes an orphan; a failed DB transaction is compensated.
                try {
                    Integer referenced = db.queryForObject("SELECT COUNT(*) FROM external_resource_version WHERE object_key=?", Integer.class, objectKey);
                    if (uploaded && (referenced == null || referenced == 0)) storage.delete(objectKey);
                    db.update("DELETE FROM external_resource_object_lease WHERE object_key=?",objectKey);
                } catch (Exception ex) { log.warn("event=external.resource.compensation.pending key={}", objectKey, ex); }
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private Receipt persist(PushRequest request, ResourceInput input, String filename, long size, String sha, String objectKey) {
        Long roleId = db.queryForObject("SELECT id FROM external_role WHERE source_system=? AND external_role_id=?", Long.class, request.sourceSystem(), request.role().roleId());
        List<Map<String,Object>> existing = db.queryForList("SELECT id,resource_type,current_version_id FROM external_resource WHERE source_system=? AND external_resource_id=? FOR UPDATE", request.sourceSystem(), input.resourceId());
        boolean isNew = existing.isEmpty();
        if (isNew) db.update("INSERT INTO external_resource(source_system,external_resource_id,resource_type,name,description) VALUES(?,?,?,?,?)", request.sourceSystem(), input.resourceId(), input.resourceType(), input.resourceName(), input.description());
        else if (!input.resourceType().equals(existing.get(0).get("resource_type"))) throw new ResourceFailure("RESOURCE_TYPE_CONFLICT", "相同资源 ID 的类型不可改变");
        Long resourceId = db.queryForObject("SELECT id FROM external_resource WHERE source_system=? AND external_resource_id=?", Long.class, request.sourceSystem(), input.resourceId());
        db.update("INSERT IGNORE INTO external_role_resource(role_id,resource_id) VALUES(?,?)", roleId, resourceId);
        db.update("UPDATE external_resource SET name=?,description=? WHERE id=?", input.resourceName(), input.description(), resourceId);
        List<Map<String,Object>> versions = db.queryForList("SELECT id,sha256,external_version FROM external_resource_version WHERE resource_id=? ORDER BY id DESC", resourceId);
        for (Map<String,Object> version : versions) {
            if (sha.equals(version.get("sha256"))) return new Receipt("UNCHANGED", ((Number)version.get("id")).longValue());
            if (!blank(input.version()) && input.version().equals(version.get("external_version")))
                throw new ResourceFailure("VERSION_CONTENT_CONFLICT", "同一版本号对应不同文件内容");
        }
        db.update("INSERT INTO external_resource_version(resource_id,external_version,file_name,size_bytes,sha256,object_key,storage_status) VALUES(?,?,?,?,?,?,'AVAILABLE')", resourceId, input.version(), filename, size, sha, objectKey);
        Long versionId = db.queryForObject("SELECT id FROM external_resource_version WHERE object_key=?", Long.class, objectKey);
        db.update("UPDATE external_resource SET current_version_id=? WHERE id=?", versionId, resourceId);
        return new Receipt(isNew ? "CREATED" : "UPDATED", versionId);
    }

    private void persistRole(PushRequest request) {
        RoleInput role = request.role();
        if (role.status() == null) {
            // On insert the DB default initializes ACTIVE; on update an omitted status is preserved.
            db.update("INSERT INTO external_role(source_system,external_role_id,role_name) VALUES(?,?,?) " +
                            "ON DUPLICATE KEY UPDATE role_name=VALUES(role_name)",
                    request.sourceSystem(), role.roleId(), role.roleName());
        } else {
            db.update("INSERT INTO external_role(source_system,external_role_id,role_name,status) VALUES(?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status),updated_at=CURRENT_TIMESTAMP",
                    request.sourceSystem(), role.roleId(), role.roleName(), role.status());
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    /** Recovers objects left behind when the process exits between MinIO and the database commit. */
    @Scheduled(fixedDelayString="${integration.external.orphan-sweep-ms:3600000}")
    public void removeStaleOrphans() {
        for (String objectKey: db.query("SELECT object_key FROM external_resource_object_lease WHERE created_at < DATE_SUB(NOW(), INTERVAL 1 HOUR) LIMIT 100",(rs,n)->rs.getString(1))) {
            try {
                Integer referenced=db.queryForObject("SELECT COUNT(*) FROM external_resource_version WHERE object_key=?",Integer.class,objectKey);
                if (referenced==null || referenced==0) storage.delete(objectKey);
                db.update("DELETE FROM external_resource_object_lease WHERE object_key=?",objectKey);
            } catch (Exception ex) { log.warn("event=external.resource.orphan.cleanup_failed key={}",objectKey,ex); }
        }
    }
    private record Receipt(String status, Long versionId) {}
    public record RoleInput(String roleId, String roleName, String status) {}
    public record ResourceInput(String resourceId, String resourceName, String resourceType, String version, String description, String fileKey) {}
    public record PushRequest(String sourceSystem, RoleInput role, List<ResourceInput> resources) {}
    public record Failure(String resourceId, String fileKey, String errorCode, String reason) {}
    public record Success(String resourceId, Long receivedVersionId, String status) {}
    public record PushResult(int total, int created, int updated, int unchanged, int failed, List<Failure> failures, List<Success> successes, String requestId) {}
    private static class ResourceFailure extends RuntimeException {
        final String code;
        ResourceFailure(String code, String message) { super(message); this.code = code; }
    }
}

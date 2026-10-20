package com.company.skillplatform.collabproject.asdm.application;

import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabException;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmTokenCipher;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CollabAsdmConfigurationService {
    private final JdbcTemplate jdbc;
    private final CollabAsdmProperties properties;
    private final CollabAsdmGitlabClient gitlab;
    private final CollabAsdmTokenCipher cipher;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public CollabAsdmConfigurationService(JdbcTemplate jdbc, CollabAsdmProperties properties,
            CollabAsdmGitlabClient gitlab, CollabAsdmTokenCipher cipher, ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.gitlab = gitlab;
        this.cipher = cipher;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public AsdmStatus get(String groupKey, long actorId) {
        Map<String, Object> group = group(groupKey);
        if (group == null) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND);
        requireGroupView(group, actorId);
        Long id = number(group.get("id"));
        String repositoryUrl = (String) group.get("asdm_repository_url");
        CredentialRow credential = credential(id);
        String credentialStatus = credential == null ? "NOT_CONFIGURED" : credential.status();
        String monitoring = repositoryUrl == null ? "DISABLED"
                : !properties.enabled() ? "MODULE_DISABLED"
                : "VALID".equals(credentialStatus) ? "RUNNING" : "PAUSED";
        Long projectId = number(group.get("asdm_gitlab_project_id"));
        return new AsdmStatus(repositoryUrl != null, repositoryUrl, credentialStatus,
                credential == null ? null : credential.lastValidatedAt(), monitoring,
                projectId == null || projectId <= 0 ? null : projectId, credential == null ? 0 : credential.versionNo(),
                credential == null ? null : credential.lastErrorCode());
    }

    public CredentialView configureCredential(String groupKey, long actorId, String accessToken, int expectedVersion) {
        Map<String, Object> group = group(groupKey);
        if (group == null) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND);
        requireOwner(group, actorId);
        requireEnabled();
        long groupId = number(group.get("id"));
        String repositoryUrl = (String) group.get("asdm_repository_url");
        if (repositoryUrl == null) throw error("ASDM_REPOSITORY_REQUIRED", "Configure an ASDM repository before adding a token", HttpStatus.CONFLICT);
        String token = requiredToken(accessToken);
        CredentialRow old = credential(groupId);
        int currentVersion = old == null ? 0 : old.versionNo();
        if (expectedVersion != currentVersion) throw error("ASDM_CREDENTIAL_VERSION_CONFLICT", "ASDM credential changed; reload and retry", HttpStatus.CONFLICT);

        CollabAsdmGitlabClient.ProjectInfo project;
        try { project = gitlab.validateRepository(repositoryUrl, token); }
        catch (CollabAsdmGitlabException ex) { throw gitlabBusinessError(ex); }
        catch (IllegalArgumentException ex) { throw error("ASDM_REPOSITORY_URL_INVALID", "The configured ASDM repository URL is invalid", HttpStatus.BAD_REQUEST); }

        String normalizedRepository = gitlab.normalizeRepositoryUrl(repositoryUrl);
        String encrypted = cipher.encrypt(token);
        return transactions.execute(tx -> {
            Map<String, Object> locked = groupForUpdate(groupKey);
            requireOwner(locked, actorId);
            long lockedGroupId = number(locked.get("id"));
            if (!Objects.equals(normalizedRepository, locked.get("asdm_repository_url"))) throw error("ASDM_REPOSITORY_CHANGED", "ASDM repository changed during token validation; retry", HttpStatus.CONFLICT);
            CredentialRow latest = credential(lockedGroupId);
            int latestVersion = latest == null ? 0 : latest.versionNo();
            if (latestVersion != expectedVersion) throw error("ASDM_CREDENTIAL_VERSION_CONFLICT", "ASDM credential changed; reload and retry", HttpStatus.CONFLICT);
            Instant now = Instant.now();
            int nextVersion = latestVersion + 1;
            jdbc.update("update collab_group set asdm_gitlab_project_id=?,version_no=version_no+1,time_updated=? where id=?", project.id(), now, lockedGroupId);
            jdbc.update("insert into collab_asdm_credential(group_id,token_ciphertext,encryption_key_version,status,last_validated_at,expires_at,last_error_code,failure_notified_at,updated_by,version_no,time_created,time_updated) values(?,?,1,'VALID',?,NULL,NULL,NULL,?,?,?,?) on duplicate key update token_ciphertext=values(token_ciphertext),encryption_key_version=1,status='VALID',last_validated_at=values(last_validated_at),last_error_code=NULL,failure_notified_at=NULL,updated_by=values(updated_by),version_no=values(version_no),time_updated=values(time_updated)",
                    lockedGroupId, encrypted, now, actorId, nextVersion, now, now);
            enqueueRescan(lockedGroupId, nextVersion, now);
            audit(lockedGroupId, actorId, Map.of("action", latest == null ? "ASDM_CREDENTIAL_VALIDATED" : "ASDM_CREDENTIAL_REPLACED", "gitlabProjectId", project.id(), "versionNo", nextVersion));
            return new CredentialView(true, "VALID", project.id(), now, null, nextVersion, null);
        });
    }

    public CredentialView revalidate(String groupKey, long actorId) {
        Map<String, Object> group = group(groupKey);
        if (group == null) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND);
        requireOwner(group, actorId);
        requireEnabled();
        long groupId = number(group.get("id"));
        String repositoryUrl = (String) group.get("asdm_repository_url");
        if (repositoryUrl == null) throw error("ASDM_REPOSITORY_REQUIRED", "Configure an ASDM repository before validation", HttpStatus.CONFLICT);
        CredentialRow credential = credential(groupId);
        if (credential == null) throw error("ASDM_CREDENTIAL_NOT_CONFIGURED", "Configure a Project Access Token first", HttpStatus.CONFLICT);
        String token = cipher.decrypt(credential.ciphertext());
        CollabAsdmGitlabClient.ProjectInfo project;
        try { project = gitlab.validateRepository(repositoryUrl, token); }
        catch (CollabAsdmGitlabException ex) {
            if (ex.httpStatus() == 401 || ex.httpStatus() == 403) {
                String state = ex.httpStatus() == 401 ? "INVALID_TOKEN" : "PERMISSION_DENIED";
                transactions.executeWithoutResult(tx -> {
                    int changed = jdbc.update("update collab_asdm_credential set status=?,last_error_code=?,time_updated=now(3) where group_id=? and version_no=?", state, ex.errorCode(), groupId, credential.versionNo());
                    if (changed == 1) audit(groupId, actorId, Map.of("action", "ASDM_CREDENTIAL_INVALID", "errorCode", ex.errorCode()));
                });
            }
            throw gitlabBusinessError(ex);
        }
        String normalizedRepository = gitlab.normalizeRepositoryUrl(repositoryUrl);
        return transactions.execute(tx -> {
            Map<String, Object> locked = groupForUpdate(groupKey);
            requireOwner(locked, actorId);
            if (!Objects.equals(normalizedRepository, locked.get("asdm_repository_url"))) throw error("ASDM_REPOSITORY_CHANGED", "ASDM repository changed during token validation; retry", HttpStatus.CONFLICT);
            CredentialRow latest = credential(groupId);
            if (latest == null || latest.versionNo() != credential.versionNo()) throw error("ASDM_CREDENTIAL_VERSION_CONFLICT", "ASDM credential changed; retry validation", HttpStatus.CONFLICT);
            Instant now = Instant.now();
            jdbc.update("update collab_group set asdm_gitlab_project_id=?,time_updated=? where id=?", project.id(), now, groupId);
            jdbc.update("update collab_asdm_credential set status='VALID',last_validated_at=?,last_error_code=NULL,failure_notified_at=NULL,updated_by=?,version_no=version_no+1,time_updated=? where group_id=? and version_no=?", now, actorId, now, groupId, credential.versionNo());
            int version = credential.versionNo() + 1;
            enqueueRescan(groupId, version, now);
            audit(groupId, actorId, Map.of("action", "ASDM_CREDENTIAL_VALIDATED", "gitlabProjectId", project.id(), "versionNo", version));
            return new CredentialView(true, "VALID", project.id(), now, null, version, null);
        });
    }

    @Transactional
    public AsdmStatus updateRepository(String groupKey, long actorId, String repositoryUrl, int expectedVersion) {
        Map<String, Object> group = groupForUpdate(groupKey);
        requireOwner(group, actorId);
        requireEnabled();
        int version = ((Number) group.get("version_no")).intValue();
        if (version != expectedVersion) throw error("COLLAB_GROUP_VERSION_CONFLICT", "Project group changed; reload and retry", HttpStatus.CONFLICT);
        String normalized;
        try { normalized = gitlab.normalizeRepositoryUrl(repositoryUrl); }
        catch (IllegalArgumentException ex) { throw error("ASDM_REPOSITORY_URL_INVALID", "ASDM repository must be a concrete HTTPS GitLab project URL allowed in this environment", HttpStatus.BAD_REQUEST); }
        long groupId = number(group.get("id"));
        Integer requirementCount = jdbc.queryForObject("select count(*) from collab_project where group_id=?", Integer.class, groupId);
        if (requirementCount != null && requirementCount > 0 && !Objects.equals(normalized, group.get("asdm_repository_url"))) {
            throw error("ASDM_REPOSITORY_LOCKED", "ASDM repository cannot change after a requirement has been created", HttpStatus.CONFLICT);
        }
        Instant now = Instant.now();
        if (!Objects.equals(normalized, group.get("asdm_repository_url"))) {
            jdbc.update("update collab_group set asdm_repository_url=?,asdm_gitlab_project_id=NULL,version_no=version_no+1,time_updated=? where id=? and version_no=?", normalized, now, groupId, expectedVersion);
            jdbc.update("delete from collab_asdm_credential where group_id=?", groupId);
            audit(groupId, actorId, Map.of("action", "ASDM_CONFIGURED", "repositoryUrl", normalized == null ? "" : normalized));
        }
        return get(groupKey, actorId);
    }

    @Transactional(readOnly = true)
    public RepositoryAccess requireAccess(long groupId) {
        Map<String, Object> group = jdbc.query("select asdm_repository_url,asdm_gitlab_project_id from collab_group where id=?", rs -> {
            if (!rs.next()) return null;
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("repository", rs.getString("asdm_repository_url"));
            row.put("project", rs.getObject("asdm_gitlab_project_id") == null ? 0L : rs.getLong("asdm_gitlab_project_id"));
            return row;
        }, groupId);
        if (group == null || group.get("repository") == null) throw error("ASDM_REPOSITORY_REQUIRED", "ASDM repository is not configured", HttpStatus.CONFLICT);
        CredentialRow credential = credential(groupId);
        if (credential == null || !"VALID".equals(credential.status())) throw error("ASDM_CREDENTIAL_NOT_VALID", "A valid ASDM GitLab credential is required", HttpStatus.CONFLICT);
        long projectId = number(group.get("project"));
        if (projectId <= 0) throw error("ASDM_PROJECT_NOT_VALIDATED", "Validate the ASDM repository credential first", HttpStatus.CONFLICT);
        String repository = (String) group.get("repository");
        return new RepositoryAccess(projectId, repository, gitlab.repositoryBase(repository), cipher.decrypt(credential.ciphertext()), credential.versionNo());
    }

    public String normalizeRepositoryUrl(String url) {
        requireEnabled();
        try { return gitlab.normalizeRepositoryUrl(url); }
        catch (IllegalArgumentException ex) { throw error("ASDM_REPOSITORY_URL_INVALID", "ASDM repository must be a concrete HTTPS GitLab project URL allowed in this environment", HttpStatus.BAD_REQUEST); }
    }

    public boolean isEnabled() { return properties.enabled(); }
    public int maxTreeEntries() { return properties.maxTreeEntries(); }

    private void enqueueRescan(long groupId, int credentialVersion, Instant now) {
        jdbc.update("insert ignore into collab_asdm_scan_task(group_id,requirement_id,task_type,dedupe_key,status,available_at,time_created,time_updated) select ?,p.id,'RESCAN',concat('asdm:rescan:',p.id,':credentialVersion:',?), 'PENDING',?,?,? from collab_project p where p.group_id=? and p.asdm_branch is not null and p.status='ACTIVE'",
                groupId, credentialVersion, now, now, now, groupId);
    }

    private void audit(long groupId, long actorId, Object change) {
        try { jdbc.update("insert into collab_group_template_audit(group_id,version_no,actor_id,changed_json,time_created) values(?,(select template_version from collab_group where id=?),?,?,now(3))", groupId, groupId, actorId, mapper.writeValueAsString(change)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize ASDM audit event", ex); }
    }

    private CredentialRow credential(long groupId) {
        return jdbc.query("select token_ciphertext,status,last_validated_at,version_no,last_error_code from collab_asdm_credential where group_id=?", rs -> rs.next() ? new CredentialRow(rs.getString("token_ciphertext"), rs.getString("status"), rs.getTimestamp("last_validated_at") == null ? null : rs.getTimestamp("last_validated_at").toInstant(), rs.getInt("version_no"), rs.getString("last_error_code")) : null, groupId);
    }

    private Map<String, Object> group(String key) {
        return jdbc.query("select g.*,owner.display_name owner_name from collab_group g join iam_user owner on owner.id=g.owner_user_id where g.group_key=?", rs -> {
            if (!rs.next()) return null;
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("id", rs.getLong("id")); row.put("group_key", rs.getString("group_key"));
            row.put("owner_user_id", rs.getLong("owner_user_id")); row.put("status", rs.getString("status"));
            row.put("asdm_repository_url", rs.getString("asdm_repository_url"));
            row.put("asdm_gitlab_project_id", rs.getObject("asdm_gitlab_project_id") == null ? 0L : rs.getLong("asdm_gitlab_project_id"));
            row.put("version_no", rs.getInt("version_no"));
            return row;
        }, key);
    }

    private Map<String, Object> groupForUpdate(String key) {
        Map<String, Object> group = group(key);
        if (group == null) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND);
        jdbc.queryForObject("select id from collab_group where id=? for update", Long.class, number(group.get("id")));
        return group(key);
    }

    private void requireGroupView(Map<String, Object> group, long actorId) {
        if (Objects.equals(number(group.get("owner_user_id")), actorId)) return;
        Integer count = jdbc.queryForObject("select count(*) from collab_group_member m join iam_user u on u.id=m.user_id where m.group_id=? and m.user_id=? and m.status='ACTIVE' and u.status='ACTIVE' and ?='PUBLISHED'", Integer.class, number(group.get("id")), actorId, group.get("status"));
        if (count == null || count == 0) throw error("COLLAB_GROUP_NOT_FOUND", "Project group not found", HttpStatus.NOT_FOUND);
    }

    private void requireOwner(Map<String, Object> group, long actorId) {
        if (!Objects.equals(number(group.get("owner_user_id")), actorId)) throw error("COLLAB_GROUP_OWNER_REQUIRED", "Project group owner is required", HttpStatus.FORBIDDEN);
    }

    private void requireEnabled() {
        if (!properties.enabled()) throw error("ASDM_MODULE_DISABLED", "ASDM GitLab integration is disabled in this environment", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private String requiredToken(String token) {
        if (token == null || token.isBlank() || token.length() > 4096 || token.chars().anyMatch(Character::isISOControl)) throw error("ASDM_TOKEN_REQUIRED", "A valid Project Access Token is required", HttpStatus.BAD_REQUEST);
        return token.trim();
    }

    private BusinessException gitlabBusinessError(CollabAsdmGitlabException ex) {
        HttpStatus status = ex.httpStatus() == 401 || ex.httpStatus() == 403 ? HttpStatus.UNPROCESSABLE_ENTITY
                : ex.httpStatus() == 404 ? HttpStatus.UNPROCESSABLE_ENTITY
                : ex.httpStatus() == 429 || ex.httpStatus() >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        return error(ex.errorCode(), "GitLab validation failed (" + ex.errorCode() + ")", status);
    }

    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static long number(Object value, long fallback) { return value == null ? fallback : ((Number) value).longValue(); }

    private record CredentialRow(String ciphertext, String status, Instant lastValidatedAt, int versionNo, String lastErrorCode) {}
    public record AsdmStatus(boolean enabled, String repositoryUrl, String credentialStatus, Instant lastValidatedAt,
            String monitoringStatus, Long gitlabProjectId, int versionNo, String lastErrorCode) {}
    public record CredentialView(boolean configured, String status, long gitlabProjectId, Instant lastValidatedAt,
            Instant expiresAt, int versionNo, String lastErrorCode) {}
    public record RepositoryAccess(long projectId, String repositoryUrl, URI repositoryBase, String token, int credentialVersion) {}
}

package com.company.skillplatform.collabproject.asdm.publication;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence boundary for immutable ASDM manifests and their per-file delivery state. */
@Repository
public class CollabAsdmPackageRepository {
    private final JdbcTemplate jdbc;

    public CollabAsdmPackageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long createPackage(Manifest m) {
        jdbc.update("INSERT INTO collab_asdm_package(requirement_id,stage_id,attempt_no,candidate_id,candidate_revision,submission_id,source_commit_sha,manifest_hash,rule_version,package_status,source_index_artifact_version_id,confirmed_by,confirmed_at,time_created,time_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(3),NOW(3))",
                m.requirementId(), m.stageId(), m.attemptNo(), m.candidateId(), m.candidateRevision(), m.submissionId(),
                m.sourceCommitSha(), m.manifestHash(), m.ruleVersion(), valueOr(m.packageStatus(), "PREPARING"),
                m.sourceIndexArtifactVersionId(), m.confirmedBy(), Timestamp.from(m.confirmedAt()));
        return jdbc.queryForObject("SELECT id FROM collab_asdm_package WHERE stage_id=? AND attempt_no=?", Long.class,
                m.stageId(), m.attemptNo());
    }

    /** Inserts idempotently; the unique path hash prevents duplicate entries after worker recovery. */
    public List<Long> addFiles(long packageId, List<FileRecord> files) {
        for (FileRecord f : files) {
            jdbc.update("INSERT IGNORE INTO collab_asdm_package_file(package_id,source_path_sha256,source_path,source_file_sha256,source_blob_sha,source_size_bytes,media_type,classification,association_score,association_reason_json,delivery_mode,mandatory_publish,snapshot_storage_key,source_git_url,publish_status,time_created,time_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(3),NOW(3))",
                    packageId, f.sourcePathSha256(), f.sourcePath(), f.sourceFileSha256(), f.sourceBlobSha(), f.sourceSizeBytes(),
                    f.mediaType(), f.classification(), f.associationScore(), f.associationReasonJson(), f.deliveryMode(),
                    f.mandatoryPublish(), f.snapshotStorageKey(),f.sourceGitUrl(), valueOr(f.publishStatus(), "PENDING"));
        }
        return jdbc.query("SELECT id FROM collab_asdm_package_file WHERE package_id=? ORDER BY source_path", (rs,n)->rs.getLong(1), packageId);
    }

    public Optional<Manifest> findPackage(long id) {
        return jdbc.query("SELECT id,requirement_id,stage_id,attempt_no,candidate_id,candidate_revision,submission_id,source_commit_sha,manifest_hash,rule_version,package_status,source_index_artifact_version_id,stage_wiki_node_token,stage_wiki_obj_token,confirmed_by,confirmed_at,completed_at,last_error_code FROM collab_asdm_package WHERE id=?",
                rs -> rs.next() ? Optional.of(new Manifest(rs.getLong("id"), rs.getLong("requirement_id"), rs.getLong("stage_id"),
                        rs.getInt("attempt_no"), rs.getLong("candidate_id"), rs.getInt("candidate_revision"), nullableLong(rs,"submission_id"),
                        rs.getString("source_commit_sha"), rs.getString("manifest_hash"), rs.getString("rule_version"),
                        rs.getString("package_status"), nullableLong(rs,"source_index_artifact_version_id"),rs.getString("stage_wiki_node_token"),rs.getString("stage_wiki_obj_token"),rs.getLong("confirmed_by"),
                        rs.getTimestamp("confirmed_at").toInstant(), instantOrNull(rs,"completed_at"), rs.getString("last_error_code"))) : Optional.empty(), id);
    }

    public Optional<IndexPublication> findIndexPublication(Long artifactVersionId) {
        if (artifactVersionId == null) return Optional.empty();
        return jdbc.query("SELECT publish_status,url FROM collab_feishu_publication WHERE artifact_version_id=?",
                rs -> rs.next() ? Optional.of(new IndexPublication(rs.getString("publish_status"), rs.getString("url"))) : Optional.empty(), artifactVersionId);
    }

    public Optional<String> findPublishedIndexNode(Long artifactVersionId) {
        if (artifactVersionId == null) return Optional.empty();
        return jdbc.query("SELECT node_token FROM collab_feishu_publication WHERE artifact_version_id=? AND publish_status='PUBLISHED' AND node_token IS NOT NULL",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), artifactVersionId);
    }

    public List<StageHierarchy> dueStageHierarchies(int limit) {
        int bounded=Math.max(1,Math.min(20,limit));
        return jdbc.query("SELECT p.stage_id,p.requirement_id,p.stage_wiki_node_token,a.wiki_node_token archive_node,pub.node_token index_node "
                +"FROM collab_asdm_package p JOIN collab_feishu_publication pub ON pub.artifact_version_id=p.source_index_artifact_version_id "
                +"LEFT JOIN collab_asdm_stage_archive a ON a.stage_id=p.stage_id "
                +"LEFT JOIN collab_asdm_stage_hierarchy h ON h.stage_id=p.stage_id "
                +"WHERE p.id=(SELECT MAX(p2.id) FROM collab_asdm_package p2 WHERE p2.stage_id=p.stage_id AND p2.stage_wiki_node_token IS NOT NULL) "
                +"AND pub.publish_status='PUBLISHED' AND pub.node_token IS NOT NULL "
                +"AND (h.stage_id IS NULL OR NOT (h.index_node_token <=> pub.node_token AND h.stage_node_token <=> p.stage_wiki_node_token AND h.archive_node_token <=> a.wiki_node_token)) "
                +"ORDER BY p.id DESC LIMIT "+bounded,
                (rs,n)->new StageHierarchy(rs.getLong("stage_id"),rs.getLong("requirement_id"),rs.getString("stage_wiki_node_token"),rs.getString("archive_node"),rs.getString("index_node")));
    }

    public void markStageHierarchy(StageHierarchy stage) {
        jdbc.update("INSERT INTO collab_asdm_stage_hierarchy(stage_id,index_node_token,stage_node_token,archive_node_token,time_updated) VALUES(?,?,?,?,NOW(3)) "
                +"ON DUPLICATE KEY UPDATE index_node_token=VALUES(index_node_token),stage_node_token=VALUES(stage_node_token),archive_node_token=VALUES(archive_node_token),time_updated=NOW(3)",
                stage.stageId(),stage.indexNodeToken(),stage.stageNodeToken(),stage.archiveNodeToken());
    }

    public record StageHierarchy(long stageId,long requirementId,String stageNodeToken,String archiveNodeToken,String indexNodeToken) {}

    public boolean belongsToStage(long packageId, long requirementId, String stageKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM collab_asdm_package ap JOIN collab_stage s ON s.id=ap.stage_id WHERE ap.id=? AND ap.requirement_id=? AND s.stage_key=?",
                Integer.class, packageId, requirementId, stageKey);
        return count != null && count > 0;
    }

    public List<FileRecord> findFiles(long packageId) {
        return jdbc.query("SELECT id,package_id,source_path_sha256,source_path,source_file_sha256,source_blob_sha,source_size_bytes,media_type,classification,association_score,association_reason_json,delivery_mode,mandatory_publish,snapshot_storage_key,source_git_url,publish_status,feishu_target_id,feishu_node_token,feishu_url,last_error_code,attempts FROM collab_asdm_package_file WHERE package_id=? ORDER BY source_path",
                (rs,n)->new FileRecord(rs.getLong("id"),rs.getLong("package_id"),rs.getString("source_path_sha256"),rs.getString("source_path"),
                        rs.getString("source_file_sha256"),rs.getString("source_blob_sha"),rs.getLong("source_size_bytes"),rs.getString("media_type"),
                        rs.getString("classification"),nullableInt(rs,"association_score"),rs.getString("association_reason_json"),rs.getString("delivery_mode"),
                        rs.getBoolean("mandatory_publish"),rs.getString("snapshot_storage_key"),rs.getString("source_git_url"),rs.getString("publish_status"),rs.getString("feishu_target_id"),
                        rs.getString("feishu_node_token"),rs.getString("feishu_url"),rs.getString("last_error_code"),rs.getInt("attempts")), packageId);
    }

    public boolean markFileStatus(long fileId, String expected, String status, String targetId, String url, String error) {
        return jdbc.update("UPDATE collab_asdm_package_file SET publish_status=?,feishu_target_id=COALESCE(?,feishu_target_id),feishu_url=COALESCE(?,feishu_url),last_error_code=?,version_no=version_no+1,time_updated=NOW(3) WHERE id=? AND publish_status=?",
                status,targetId,url,error,fileId,expected) == 1;
    }

    public boolean recordRemoteTarget(long fileId,String targetId) {
        return jdbc.update("UPDATE collab_asdm_package_file SET feishu_target_id=?,version_no=version_no+1,time_updated=NOW(3) WHERE id=? AND publish_status='UPLOADING' AND feishu_target_id IS NULL",targetId,fileId)==1;
    }
    public boolean recordMountedNode(long fileId,String nodeToken,String url) {
        return jdbc.update("UPDATE collab_asdm_package_file SET feishu_node_token=?,feishu_url=?,version_no=version_no+1,time_updated=NOW(3) WHERE id=? AND publish_status='UPLOADING' AND feishu_node_token IS NULL",nodeToken,url,fileId)==1;
    }

    public void markPackageStatus(long id, String status, String error) {
        jdbc.update("UPDATE collab_asdm_package SET package_status=?,last_error_code=?,completed_at=IF(?='PUBLISHED',NOW(3),completed_at),version_no=version_no+1,time_updated=NOW(3) WHERE id=?",
                status,error,status,id);
    }

    public PackageProgress progress(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) required_total,COALESCE(SUM(publish_status IN ('PUBLISHED','LINK_ONLY')),0) published_count,COALESCE(SUM(publish_status='FAILED'),0) failed_count,COALESCE(SUM(publish_status='RECONCILE_REQUIRED'),0) reconcile_count,COALESCE(SUM(publish_status NOT IN ('PUBLISHED','LINK_ONLY')),0) remaining_required FROM collab_asdm_package_file WHERE package_id=?",
                (rs,n)->new PackageProgress(rs.getInt("required_total"),rs.getInt("published_count"),rs.getInt("failed_count"),rs.getInt("reconcile_count"),rs.getInt("remaining_required")==0), id);
    }

    public List<Long> duePackageIds(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT p.id FROM collab_asdm_package p WHERE p.package_status IN ('PUBLISHING_FILES','PUBLISHING_INDEX','PARTIAL_FAILED') ORDER BY p.id LIMIT " + bounded,
                (rs,n)->rs.getLong(1));
    }

    public List<Long> dueFileIds(long packageId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT id FROM collab_asdm_package_file WHERE package_id=? AND publish_status='PENDING' AND next_attempt_at<=NOW(3) ORDER BY id LIMIT " + bounded,
                (rs,n)->rs.getLong(1),packageId);
    }

    public Optional<FileRecord> findFile(long id) {
        return findFilesByQuery("SELECT id,package_id,source_path_sha256,source_path,source_file_sha256,source_blob_sha,source_size_bytes,media_type,classification,association_score,association_reason_json,delivery_mode,mandatory_publish,snapshot_storage_key,source_git_url,publish_status,feishu_target_id,feishu_node_token,feishu_url,last_error_code,attempts FROM collab_asdm_package_file WHERE id=?",id);
    }

    public void scheduleRetry(long fileId, String error, long delaySeconds) {
        jdbc.update("UPDATE collab_asdm_package_file SET publish_status='PENDING',last_error_code=?,next_attempt_at=DATE_ADD(NOW(3),INTERVAL ? SECOND),time_updated=NOW(3) WHERE id=? AND publish_status NOT IN ('PUBLISHED','LINK_ONLY')",
                error,Math.max(1,Math.min(delaySeconds,86400)),fileId);
    }

    public void resetFailedFiles(long packageId) {
        jdbc.update("UPDATE collab_asdm_package_file SET publish_status='PENDING',last_error_code=NULL,next_attempt_at=NOW(3),version_no=version_no+1,time_updated=NOW(3) WHERE package_id=? AND publish_status='FAILED'",packageId);
        jdbc.update("UPDATE collab_asdm_package SET package_status='PUBLISHING_FILES',last_error_code=NULL,time_updated=NOW(3) WHERE id=? AND package_status IN ('PARTIAL_FAILED','RECONCILE_REQUIRED')",packageId);
    }

    public void recoverInterruptedUploads() {
        jdbc.update("UPDATE collab_asdm_package_file SET publish_status='RECONCILE_REQUIRED',last_error_code='ASDM_FEISHU_RECONCILE_REQUIRED',version_no=version_no+1,time_updated=NOW(3) WHERE publish_status='UPLOADING' AND time_updated<DATE_SUB(NOW(3),INTERVAL 5 MINUTE)");
        jdbc.update("UPDATE collab_asdm_package p SET p.package_status='RECONCILE_REQUIRED',p.last_error_code='ASDM_FEISHU_RECONCILE_REQUIRED',p.version_no=p.version_no+1,p.time_updated=NOW(3) WHERE p.package_status IN ('PUBLISHING_FILES','PUBLISHING_INDEX') AND EXISTS(SELECT 1 FROM collab_asdm_package_file f WHERE f.package_id=p.id AND f.publish_status='RECONCILE_REQUIRED')");
    }

    public boolean claimFile(long fileId) {
        return jdbc.update("UPDATE collab_asdm_package_file SET publish_status='UPLOADING',attempts=attempts+1,version_no=version_no+1,time_updated=NOW(3) WHERE id=? AND publish_status='PENDING' AND next_attempt_at<=NOW(3)",fileId)==1;
    }

    public void saveStageFolder(long packageId,String nodeToken,String objectToken) {
        jdbc.update("UPDATE collab_asdm_package SET stage_wiki_node_token=?,stage_wiki_obj_token=?,time_updated=NOW(3) WHERE id=? AND stage_wiki_node_token IS NULL",nodeToken,objectToken,packageId);
    }

    public Optional<WikiFolder> previousStageFolder(long stageId,int attemptNo) {
        return jdbc.query("SELECT stage_wiki_node_token,stage_wiki_obj_token FROM collab_asdm_package WHERE stage_id=? AND attempt_no<? AND stage_wiki_node_token IS NOT NULL AND stage_wiki_obj_token IS NOT NULL ORDER BY attempt_no DESC LIMIT 1",
                rs->rs.next()?Optional.of(new WikiFolder(rs.getString(1),rs.getString(2))):Optional.empty(),stageId,attemptNo);
    }

    public Optional<PreviousFile> previousPublishedFile(long stageId,int attemptNo,String sourcePathSha256) {
        return jdbc.query("SELECT f.delivery_mode,f.source_file_sha256,f.feishu_target_id,f.feishu_node_token,f.feishu_url FROM collab_asdm_package_file f JOIN collab_asdm_package p ON p.id=f.package_id WHERE p.stage_id=? AND p.attempt_no<? AND f.source_path_sha256=? AND f.publish_status='PUBLISHED' AND f.feishu_target_id IS NOT NULL ORDER BY p.attempt_no DESC LIMIT 1",
                rs->rs.next()?Optional.of(new PreviousFile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5))):Optional.empty(),stageId,attemptNo,sourcePathSha256);
    }

    public record WikiFolder(String nodeToken,String objectToken) {}
    public record PreviousFile(String deliveryMode,String sha256,String targetId,String nodeToken,String url) {}

    public Optional<WikiFolder> stageArchive(long stageId) {
        return jdbc.query("SELECT wiki_node_token,wiki_obj_token FROM collab_asdm_stage_archive WHERE stage_id=?",
                rs->rs.next()?Optional.of(new WikiFolder(rs.getString(1),rs.getString(2))):Optional.empty(),stageId);
    }

    public void saveStageArchive(long stageId,String nodeToken,String objectToken) {
        jdbc.update("INSERT INTO collab_asdm_stage_archive(stage_id,wiki_node_token,wiki_obj_token) VALUES(?,?,?)",stageId,nodeToken,objectToken);
    }

    public void setPackageStatus(long packageId,String expected,String status,String error) {
        jdbc.update("UPDATE collab_asdm_package SET package_status=?,last_error_code=?,version_no=version_no+1,time_updated=NOW(3) WHERE id=? AND package_status=?",status,error,packageId,expected);
    }

    private Optional<FileRecord> findFilesByQuery(String sql,long id) {
        return jdbc.query(sql,rs->rs.next()?Optional.of(new FileRecord(rs.getLong("id"),rs.getLong("package_id"),rs.getString("source_path_sha256"),rs.getString("source_path"),
                rs.getString("source_file_sha256"),rs.getString("source_blob_sha"),rs.getLong("source_size_bytes"),rs.getString("media_type"),rs.getString("classification"),
                nullableInt(rs,"association_score"),rs.getString("association_reason_json"),rs.getString("delivery_mode"),rs.getBoolean("mandatory_publish"),rs.getString("snapshot_storage_key"),rs.getString("source_git_url"),
                rs.getString("publish_status"),rs.getString("feishu_target_id"),rs.getString("feishu_node_token"),rs.getString("feishu_url"),rs.getString("last_error_code"),rs.getInt("attempts"))):Optional.empty(),id);
    }
    private static Long nullableLong(java.sql.ResultSet rs,String key)throws java.sql.SQLException { long x=rs.getLong(key); return rs.wasNull()?null:x; }
    private static Integer nullableInt(java.sql.ResultSet rs,String key)throws java.sql.SQLException { int x=rs.getInt(key); return rs.wasNull()?null:x; }
    private static Instant instantOrNull(java.sql.ResultSet rs,String key)throws java.sql.SQLException { Timestamp t=rs.getTimestamp(key); return t==null?null:t.toInstant(); }
    private static String valueOr(String a,String b){return a==null||a.isBlank()?b:a;}

    public record Manifest(Long id,long requirementId,long stageId,int attemptNo,long candidateId,int candidateRevision,Long submissionId,
            String sourceCommitSha,String manifestHash,String ruleVersion,String packageStatus,Long sourceIndexArtifactVersionId,
            String stageWikiNodeToken,String stageWikiObjToken,long confirmedBy,Instant confirmedAt,Instant completedAt,String lastErrorCode) {
        public Manifest(long requirementId,long stageId,int attemptNo,long candidateId,int candidateRevision,Long submissionId,
                String sourceCommitSha,String manifestHash,String ruleVersion,String packageStatus,Long sourceIndexArtifactVersionId,
                long confirmedBy,Instant confirmedAt) {
            this(null,requirementId,stageId,attemptNo,candidateId,candidateRevision,submissionId,sourceCommitSha,manifestHash,ruleVersion,
                    packageStatus,sourceIndexArtifactVersionId,null,null,confirmedBy,confirmedAt,null,null);
        }
    }
    public record FileRecord(Long id,long packageId,String sourcePathSha256,String sourcePath,String sourceFileSha256,String sourceBlobSha,
            long sourceSizeBytes,String mediaType,String classification,Integer associationScore,String associationReasonJson,
            String deliveryMode,boolean mandatoryPublish,String snapshotStorageKey,String sourceGitUrl,String publishStatus,String feishuTargetId,
            String feishuNodeToken,String feishuUrl,String lastErrorCode,int attempts) {
        public FileRecord(long packageId,String sourcePathSha256,String sourcePath,String sourceFileSha256,String sourceBlobSha,long sourceSizeBytes,
                String mediaType,String classification,Integer associationScore,String associationReasonJson,String deliveryMode,boolean mandatoryPublish,
                String snapshotStorageKey) {
            this(null,packageId,sourcePathSha256,sourcePath,sourceFileSha256,sourceBlobSha,sourceSizeBytes,mediaType,classification,associationScore,
                    associationReasonJson,deliveryMode,mandatoryPublish,snapshotStorageKey,null,"PENDING",null,null,null,null,0);
        }
        public FileRecord(long packageId,String sourcePathSha256,String sourcePath,String sourceFileSha256,String sourceBlobSha,long sourceSizeBytes,
                String mediaType,String classification,Integer associationScore,String associationReasonJson,String deliveryMode,boolean mandatoryPublish,
                String snapshotStorageKey,String sourceGitUrl) {
            this(null,packageId,sourcePathSha256,sourcePath,sourceFileSha256,sourceBlobSha,sourceSizeBytes,mediaType,classification,associationScore,
                    associationReasonJson,deliveryMode,mandatoryPublish,snapshotStorageKey,sourceGitUrl,"PENDING",null,null,null,null,0);
        }
    }
    public record PackageProgress(int requiredTotal,int publishedCount,int failedCount,int reconcileCount,boolean allRequiredPublished) {}
    public record IndexPublication(String status, String url) {}
}

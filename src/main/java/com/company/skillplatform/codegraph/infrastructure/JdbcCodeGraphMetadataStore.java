package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.application.CodeGraphMetadataStore;
import com.company.skillplatform.codegraph.application.CodeGraphReusePlanner;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Repository
public class JdbcCodeGraphMetadataStore implements CodeGraphMetadataStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcCodeGraphMetadataStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    @Override
    public Map<String, CodeGraphReusePlanner.SnapshotMatch> findReadySnapshots(List<String> fingerprints) {
        if (fingerprints.isEmpty()) return Map.of();
        var placeholders = String.join(",", java.util.Collections.nCopies(fingerprints.size(), "?"));
        var result = new HashMap<String, CodeGraphReusePlanner.SnapshotMatch>();
        jdbc.query("SELECT id,graph_fingerprint,status FROM code_graph_repository_snapshot WHERE status='READY' AND graph_fingerprint IN (" + placeholders + ")",
                (rs, row) -> new CodeGraphReusePlanner.SnapshotMatch(rs.getLong("id"), rs.getString("graph_fingerprint"), rs.getString("status")),
                fingerprints.toArray()).forEach(match -> result.put(match.fingerprint(), match));
        return result;
    }

    @Override
    public OptionalLong findReadyBundle(String bundleHash) {
        var ids = jdbc.query("SELECT id FROM code_graph_bundle WHERE bundle_hash=? AND status='READY'", (rs, row) -> rs.getLong(1), bundleHash);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }

    @Override
    @Transactional
    public long activateReused(GenerationCommand command, CodeGraphReusePlanner.Plan plan, String bundleHash, long bundleId) {
        var bindingId = createBinding(command, bundleHash, bundleId, "PREPARING");
        var requestId = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO code_graph_generation_job(time_created,time_updated,workflow_run_id,target_binding_id,request_id,reason,status,engine_type,reuse_plan_json,build_request_json,progress,started_at,completed_at) VALUES(NOW(3),NOW(3),?,?,?,?,'READY',?,?,?,100,NOW(3),NOW(3))",
                command.workflowRunId(), bindingId, requestId, command.reason(), command.engineType(), json(plan), json(new StoredBuild(command, plan, bundleHash)));
        activateBinding(command.workflowRunId(), bindingId, bundleId);
        return jdbc.queryForObject("SELECT id FROM code_graph_generation_job WHERE request_id=?", Long.class, requestId);
    }

    @Override
    @Transactional
    public PendingJob createBuild(GenerationCommand command, CodeGraphReusePlanner.Plan plan, String bundleHash,
                                  String requestId, String engineBundleKey) {
        jdbc.update("INSERT INTO code_graph_bundle(time_created,time_updated,bundle_hash,engine_type,engine_bundle_key,repository_count,status) VALUES(NOW(3),NOW(3),?,?,?,?,'BUILDING') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),time_updated=NOW(3),status=IF(status='READY',status,'BUILDING'),last_error=NULL",
                bundleHash, command.engineType(), engineBundleKey, command.repositories().size());
        var bundleId = jdbc.queryForObject("SELECT id FROM code_graph_bundle WHERE bundle_hash=?", Long.class, bundleHash);
        var bindingId = createBinding(command, bundleHash, bundleId, "PREPARING");
        jdbc.update("INSERT INTO code_graph_generation_job(time_created,time_updated,workflow_run_id,target_binding_id,request_id,reason,status,engine_type,reuse_plan_json,build_request_json,progress,started_at) VALUES(NOW(3),NOW(3),?,?,?,?,'BUILDING',?,?,?,0,NOW(3))",
                command.workflowRunId(), bindingId, requestId, command.reason(), command.engineType(), json(plan), json(new StoredBuild(command, plan, bundleHash)));
        var jobId = jdbc.queryForObject("SELECT id FROM code_graph_generation_job WHERE request_id=?", Long.class, requestId);
        return new PendingJob(jobId, bindingId, requestId, engineBundleKey);
    }

    @Override
    public void attachEngineJob(long jobId, String engineJobId) {
        jdbc.update("UPDATE code_graph_generation_job SET engine_job_id=?,next_attempt_at=NOW(3),time_updated=NOW(3) WHERE id=? AND status='BUILDING'", engineJobId, jobId);
    }

    @Override
    public PollableJob pollable(long jobId) {
        return jdbc.queryForObject("SELECT id,workflow_run_id,engine_job_id,status FROM code_graph_generation_job WHERE id=?",
                (rs, row) -> new PollableJob(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), jobId);
    }

    @Override
    public void updateProgress(long jobId, int progress) {
        jdbc.update("UPDATE code_graph_generation_job SET progress=?,retry_count=0,last_error_code=NULL,last_error_message=NULL,time_updated=NOW(3) WHERE id=? AND status='BUILDING'",
                Math.max(0, Math.min(99, progress)), jobId);
    }

    @Override
    @Transactional
    public boolean complete(long jobId, CodeGraphEnginePort.BuildStatus status) {
        var rows = jdbc.query("SELECT target_binding_id,build_request_json FROM code_graph_generation_job WHERE id=? AND status='BUILDING' FOR UPDATE",
                (rs, row) -> new StoredJob(rs.getLong(1), read(rs.getString(2))), jobId);
        if (rows.isEmpty()) return false;
        var job = rows.get(0);
        var artifact = status.artifact();
        if (!job.build().command().engineType().equals(artifact.engine())
                || !job.build().command().engineVersion().equals(artifact.engineVersion())
                || !job.build().command().adapterVersion().equals(artifact.adapterVersion())) {
            failRows(jobId, job.bindingId(), "CODE_GRAPH_ARTIFACT_INCOMPATIBLE", "Worker artifact engine identity does not match the generation request");
            return false;
        }
        var bundleId = jdbc.queryForObject("SELECT bundle_id FROM workflow_run_code_graph_binding WHERE id=?", Long.class, job.bindingId());
        var bundleStatus = jdbc.queryForObject("SELECT status FROM code_graph_bundle WHERE id=? FOR UPDATE", String.class, bundleId);
        if ("READY".equals(bundleStatus)) {
            activateBinding(job.build().command().workflowRunId(), job.bindingId(), bundleId);
            jdbc.update("UPDATE code_graph_generation_job SET status='READY',progress=100,completed_at=NOW(3),lease_until=NULL,time_updated=NOW(3) WHERE id=? AND status='BUILDING'", jobId);
            return true;
        }
        for (var item : job.build().plan().repositories()) {
            jdbc.update("INSERT INTO code_graph_repository_snapshot(time_created,time_updated,logical_repository_key,commit_sha,tree_sha,engine_type,engine_version,adapter_version,engine_config_hash,graph_fingerprint,artifact_key,artifact_sha256,status,build_mode) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,?,?,?,?,'READY','FULL') ON DUPLICATE KEY UPDATE time_updated=NOW(3),artifact_key=VALUES(artifact_key),artifact_sha256=VALUES(artifact_sha256),status='READY',build_mode='FULL',last_error=NULL",
                    item.repository().repositoryKey(), item.repository().commitSha(), item.repository().treeSha(),
                    job.build().command().engineType(), job.build().command().engineVersion(), job.build().command().adapterVersion(),
                    job.build().command().engineConfigHash(), item.fingerprint(), artifact.uri(), artifact.sha256());
        }
        jdbc.update("DELETE FROM code_graph_bundle_repository WHERE bundle_id=?", bundleId);
        for (var item : job.build().plan().repositories()) {
            var snapshotId = jdbc.queryForObject("SELECT id FROM code_graph_repository_snapshot WHERE graph_fingerprint=? AND status='READY'", Long.class, item.fingerprint());
            jdbc.update("INSERT INTO code_graph_bundle_repository(time_created,bundle_id,repository_snapshot_id,repository_alias) VALUES(NOW(3),?,?,?)",
                    bundleId, snapshotId, item.repository().logicalName());
        }
        jdbc.update("UPDATE code_graph_bundle SET status='READY',artifact_key=?,artifact_sha256=?,time_updated=NOW(3),last_error=NULL WHERE id=?",
                artifact.uri(), artifact.sha256(), bundleId);
        activateBinding(job.build().command().workflowRunId(), job.bindingId(), bundleId);
        jdbc.update("UPDATE code_graph_generation_job SET status='READY',progress=100,completed_at=NOW(3),lease_until=NULL,time_updated=NOW(3) WHERE id=? AND status='BUILDING'", jobId);
        return true;
    }

    @Override
    @Transactional
    public void fail(long jobId, String errorCode, String errorMessage) {
        var bindingIds = jdbc.query("SELECT target_binding_id FROM code_graph_generation_job WHERE id=?", (rs, row) -> rs.getLong(1), jobId);
        if (!bindingIds.isEmpty()) failRows(jobId, bindingIds.get(0), errorCode, errorMessage);
    }

    private void failRows(long jobId, long bindingId, String errorCode, String errorMessage) {
        jdbc.update("UPDATE code_graph_generation_job SET status='FAILED',last_error_code=?,last_error_message=?,completed_at=NOW(3),lease_until=NULL,time_updated=NOW(3) WHERE id=? AND status NOT IN ('READY','CANCELLED')",
                truncate(errorCode, 100), truncate(errorMessage, 4000), jobId);
        jdbc.update("UPDATE workflow_run_code_graph_binding SET status='FAILED',time_updated=NOW(3) WHERE id=? AND status='PREPARING'", bindingId);
        jdbc.update("UPDATE code_graph_bundle b JOIN workflow_run_code_graph_binding x ON x.bundle_id=b.id SET b.status='FAILED',b.last_error=?,b.time_updated=NOW(3) WHERE x.id=? AND b.status='BUILDING'",
                truncate(errorMessage, 4000), bindingId);
    }

    private long createBinding(GenerationCommand command, String bundleHash, Long bundleId, String status) {
        jdbc.queryForObject("SELECT id FROM workflow_run WHERE id=? FOR UPDATE", Long.class, command.workflowRunId());
        var version = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM workflow_run_code_graph_binding WHERE workflow_run_id=?", Integer.class, command.workflowRunId());
        var engineBundleKey = "cg-" + bundleHash.substring(0, 24);
        jdbc.update("INSERT INTO workflow_run_code_graph_binding(time_created,time_updated,workflow_run_id,version_no,bundle_id,engine_type,engine_bundle_key,repository_set_hash,reason,status,semantic_index_status) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,?,?,'DISABLED')",
                command.workflowRunId(), version, bundleId, command.engineType(), engineBundleKey, bundleHash, command.reason(), status);
        return jdbc.queryForObject("SELECT id FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND version_no=?", Long.class, command.workflowRunId(), version);
    }

    private void activateBinding(long runId, long bindingId, long bundleId) {
        jdbc.update("UPDATE workflow_run_code_graph_binding SET status='SUPERSEDED',time_updated=NOW(3) WHERE workflow_run_id=? AND status='ACTIVE' AND id<>?", runId, bindingId);
        jdbc.update("UPDATE workflow_run_code_graph_binding SET status='ACTIVE',bundle_id=?,activated_at=NOW(3),time_updated=NOW(3) WHERE id=? AND status='PREPARING'", bundleId, bindingId);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Unable to serialize code graph metadata", exception); }
    }

    private StoredBuild read(String value) {
        try { return mapper.readValue(value, StoredBuild.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored code graph request is invalid", exception); }
    }

    private String truncate(String value, int length) {
        if (value == null) return null;
        return value.substring(0, Math.min(length, value.length()));
    }

    public record StoredBuild(GenerationCommand command, CodeGraphReusePlanner.Plan plan, String bundleHash) {}
    private record StoredJob(long bindingId, StoredBuild build) {}
}

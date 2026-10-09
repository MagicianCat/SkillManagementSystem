package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.application.CodeGraphLifecyclePublisher;
import com.company.skillplatform.codegraph.application.CodeGraphMetadataStore;
import com.company.skillplatform.codegraph.application.CodeGraphReusePlanner;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
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
    private final CodeGraphLifecyclePublisher lifecycle;

    public JdbcCodeGraphMetadataStore(JdbcTemplate jdbc, ObjectMapper mapper,
                                      @Lazy CodeGraphLifecyclePublisher lifecycle) {
        this.jdbc = jdbc; this.mapper = mapper; this.lifecycle = lifecycle;
    }

    @Override
    public Map<String, CodeGraphReusePlanner.SnapshotMatch> findReadySnapshots(List<String> fingerprints) {
        if (fingerprints.isEmpty()) return Map.of();
        var placeholders = String.join(",", java.util.Collections.nCopies(fingerprints.size(), "?"));
        var result = new HashMap<String, CodeGraphReusePlanner.SnapshotMatch>();
        jdbc.query("SELECT s.id,s.graph_fingerprint,s.status,s.commit_sha,s.tree_sha,s.artifact_key,s.artifact_sha256,s.artifact_repository_alias " +
                        "FROM code_graph_repository_snapshot s WHERE s.status='READY' AND s.artifact_key IS NOT NULL AND s.artifact_sha256 IS NOT NULL " +
                        "AND s.artifact_repository_alias IS NOT NULL " +
                        "AND s.graph_fingerprint IN (" + placeholders + ")",
                (rs, row) -> new CodeGraphReusePlanner.SnapshotMatch(rs.getLong("id"), rs.getString("graph_fingerprint"), rs.getString("status"),
                        rs.getString("commit_sha"), rs.getString("tree_sha"), rs.getString("artifact_key"), rs.getString("artifact_sha256"), rs.getString("artifact_repository_alias")),
                fingerprints.toArray()).forEach(match -> result.put(match.fingerprint(), match));
        return result;
    }

    @Override
    public List<CodeGraphReusePlanner.SnapshotCandidate> findCompatibleSnapshots(RepositoryInput repository,
                                                                                  String engineType,
                                                                                  String engineVersion,
                                                                                  String adapterVersion,
                                                                                  String engineConfigHash) {
        return jdbc.query("SELECT s.id,s.graph_fingerprint,s.status,s.commit_sha,s.tree_sha,s.artifact_key,s.artifact_sha256,s.artifact_repository_alias " +
                        "FROM code_graph_repository_snapshot s " +
                        "WHERE s.status='READY' AND s.artifact_key IS NOT NULL AND s.artifact_sha256 IS NOT NULL " +
                        "AND s.artifact_repository_alias IS NOT NULL " +
                        "AND logical_repository_key=? AND engine_type=? AND engine_version=? " +
                        "AND adapter_version=? AND engine_config_hash=? AND commit_sha<>? ORDER BY time_updated DESC",
                (rs, row) -> new CodeGraphReusePlanner.SnapshotCandidate(rs.getLong("id"), rs.getString("graph_fingerprint"),
                        rs.getString("status"), rs.getString("commit_sha"), rs.getString("tree_sha"), rs.getString("artifact_key"),
                        rs.getString("artifact_sha256"), rs.getString("artifact_repository_alias")),
                repository.repositoryKey(), engineType, engineVersion, adapterVersion, engineConfigHash, repository.commitSha());
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
        var jobType = "REPO_APPEND".equals(command.reason()) ? "REPO_APPEND" : "INITIAL";
        jdbc.update("INSERT INTO code_graph_generation_job(time_created,time_updated,workflow_run_id,target_binding_id,request_id,reason,job_type,status,engine_type,reuse_plan_json,build_request_json,progress,started_at,completed_at) VALUES(NOW(3),NOW(3),?,?,?,?,?,'READY',?,?,?,100,NOW(3),NOW(3))",
                command.workflowRunId(), bindingId, requestId, command.reason(), jobType, command.engineType(), json(plan), json(new StoredBuild(command, plan, bundleHash)));
        activateBinding(command.workflowRunId(), bindingId, bundleId);
        markWorkflowReadyIfInitial(jobType, command.workflowRunId());
        var jobId = jdbc.queryForObject("SELECT id FROM code_graph_generation_job WHERE request_id=?", Long.class, requestId);
        publishBindingActivated(command.workflowRunId(), bindingId, bundleId, jobType);
        return jobId;
    }

    @Override
    @Transactional
    public PendingJob createBuild(GenerationCommand command, CodeGraphReusePlanner.Plan plan, String bundleHash,
                                  String requestId, String engineBundleKey) {
        // Global lock order starts with workflow_run. This serialises binding version
        // allocation and avoids bundle/binding inversions with completion and append.
        lockWorkflow(command.workflowRunId());
        jdbc.update("INSERT INTO code_graph_bundle(time_created,time_updated,bundle_hash,engine_type,engine_bundle_key,repository_count,status) VALUES(NOW(3),NOW(3),?,?,?,?,'BUILDING') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),time_updated=NOW(3),status=IF(status='READY',status,'BUILDING'),last_error=NULL",
                bundleHash, command.engineType(), engineBundleKey, command.repositories().size());
        var bundleId = jdbc.queryForObject("SELECT id FROM code_graph_bundle WHERE bundle_hash=?", Long.class, bundleHash);
        var bindingId = createBinding(command, bundleHash, bundleId, "PREPARING");
        var jobType = "REPO_APPEND".equals(command.reason()) ? "REPO_APPEND" : "INITIAL";
        jdbc.update("INSERT INTO code_graph_generation_job(time_created,time_updated,workflow_run_id,target_binding_id,request_id,reason,job_type,status,engine_type,reuse_plan_json,build_request_json,progress,started_at) VALUES(NOW(3),NOW(3),?,?,?,?,?,'BUILDING',?,?,?,0,NOW(3))",
                command.workflowRunId(), bindingId, requestId, command.reason(), jobType, command.engineType(), json(plan), json(new StoredBuild(command, plan, bundleHash)));
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
        // Discover the run without locking, then follow the global lock order:
        // workflow_run -> generation_job -> bundle -> binding.
        var runIds = jdbc.query("SELECT workflow_run_id FROM code_graph_generation_job WHERE id=? AND status='BUILDING'",
                (rs, row) -> rs.getLong(1), jobId);
        if (runIds.isEmpty()) return false;
        lockWorkflow(runIds.get(0));
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
        var jobType = jobType(jobId);
        if ("READY".equals(bundleStatus)) {
            activateBinding(job.build().command().workflowRunId(), job.bindingId(), bundleId);
            markWorkflowReadyIfInitial(jobType, job.build().command().workflowRunId());
            jdbc.update("UPDATE code_graph_generation_job SET status='READY',progress=100,completed_at=NOW(3),lease_until=NULL,time_updated=NOW(3) WHERE id=? AND status='BUILDING'", jobId);
            publishBindingActivated(job.build().command().workflowRunId(), job.bindingId(), bundleId, jobType);
            return true;
        }
        for (var item : job.build().plan().repositories()) {
            if (item.decision() == CodeGraphReusePlanner.Decision.REUSE_EXACT) continue;
            var mode = item.decision() == CodeGraphReusePlanner.Decision.INCREMENTAL ? "INCREMENTAL" : "FULL";
            jdbc.update("INSERT INTO code_graph_repository_snapshot(time_created,time_updated,logical_repository_key,commit_sha,tree_sha,engine_type,engine_version,adapter_version,engine_config_hash,graph_fingerprint,base_snapshot_id,commit_distance,artifact_key,artifact_sha256,artifact_repository_alias,status,build_mode) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,?,?,?,?,?,?,?,'READY',?) ON DUPLICATE KEY UPDATE time_updated=NOW(3),base_snapshot_id=VALUES(base_snapshot_id),commit_distance=VALUES(commit_distance),artifact_key=VALUES(artifact_key),artifact_sha256=VALUES(artifact_sha256),artifact_repository_alias=VALUES(artifact_repository_alias),status='READY',build_mode=VALUES(build_mode),last_error=NULL",
                    item.repository().repositoryKey(), item.repository().commitSha(), item.repository().treeSha(),
                    job.build().command().engineType(), job.build().command().engineVersion(), job.build().command().adapterVersion(),
                    job.build().command().engineConfigHash(), item.fingerprint(), item.baseSnapshotId(), item.commitDistance(), artifact.uri(), artifact.sha256(), item.repository().logicalName(), mode);
        }
        jdbc.update("DELETE FROM code_graph_bundle_repository WHERE bundle_id=?", bundleId);
        for (var item : job.build().plan().repositories()) {
            var snapshotId = jdbc.queryForObject("SELECT id FROM code_graph_repository_snapshot WHERE graph_fingerprint=? AND status='READY'", Long.class, item.fingerprint());
            var mode = item.decision() == CodeGraphReusePlanner.Decision.REUSE_EXACT ? "REUSED" : item.decision().name();
            jdbc.update("INSERT INTO code_graph_bundle_repository(time_created,bundle_id,repository_snapshot_id,repository_alias,build_mode,base_snapshot_id) VALUES(NOW(3),?,?,?,?,?)",
                    bundleId, snapshotId, item.repository().logicalName(), mode, item.baseSnapshotId());
        }
        jdbc.update("UPDATE code_graph_bundle SET status='READY',artifact_key=?,artifact_sha256=?,time_updated=NOW(3),last_error=NULL WHERE id=?",
                artifact.uri(), artifact.sha256(), bundleId);
        activateBinding(job.build().command().workflowRunId(), job.bindingId(), bundleId);
        markWorkflowReadyIfInitial(jobType, job.build().command().workflowRunId());
        jdbc.update("UPDATE code_graph_generation_job SET status='READY',progress=100,completed_at=NOW(3),lease_until=NULL,time_updated=NOW(3) WHERE id=? AND status='BUILDING'", jobId);
        publishBindingActivated(job.build().command().workflowRunId(), job.bindingId(), bundleId, jobType);
        return true;
    }

    @Override
    @Transactional
    public void fail(long jobId, String errorCode, String errorMessage) {
        var jobs = jdbc.query("SELECT workflow_run_id,target_binding_id FROM code_graph_generation_job WHERE id=?",
                (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}, jobId);
        if (jobs.isEmpty()) return;
        lockWorkflow(jobs.get(0)[0]);
        var bindingIds = jdbc.query("SELECT target_binding_id FROM code_graph_generation_job WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), jobId);
        if (!bindingIds.isEmpty()) failRows(jobId, bindingIds.get(0), errorCode, errorMessage);
    }

    private void failRows(long jobId, long bindingId, String errorCode, String errorMessage) {
        jdbc.update("UPDATE code_graph_generation_job SET status='FAILED',last_error_code=?,last_error_message=?,completed_at=NOW(3),lease_until=NULL,time_updated=NOW(3) WHERE id=? AND status NOT IN ('READY','CANCELLED')",
                truncate(errorCode, 100), truncate(errorMessage, 4000), jobId);
        jdbc.update("UPDATE workflow_run_code_graph_binding SET status='FAILED',time_updated=NOW(3) WHERE id=? AND status='PREPARING'", bindingId);
        jdbc.update("UPDATE code_graph_bundle b JOIN workflow_run_code_graph_binding x ON x.bundle_id=b.id SET b.status='FAILED',b.last_error=?,b.time_updated=NOW(3) WHERE x.id=? AND b.status='BUILDING'",
                truncate(errorMessage, 4000), bindingId);
        // M7: only flip the workflow status for the INITIAL preparation path. A failed
        // REPO_APPEND must not disturb a RUNNING workflow nor the current ACTIVE binding.
        if (!"REPO_APPEND".equals(jobType(jobId))) {
            jdbc.update("UPDATE workflow_run w JOIN code_graph_generation_job j ON j.workflow_run_id=w.id SET w.status='CODE_GRAPH_PREPARATION_FAILED',w.failure_reason=?,w.time_updated=NOW(3) WHERE j.id=? AND w.status='PREPARING_CODE_GRAPH'",
                    truncate(errorMessage, 2000), jobId);
        }
    }

    private long createBinding(GenerationCommand command, String bundleHash, Long bundleId, String status) {
        lockWorkflow(command.workflowRunId());
        var version = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM workflow_run_code_graph_binding WHERE workflow_run_id=?", Integer.class, command.workflowRunId());
        var engineBundleKey = "cg-" + bundleHash.substring(0, 24);
        jdbc.update("INSERT INTO workflow_run_code_graph_binding(time_created,time_updated,workflow_run_id,version_no,bundle_id,engine_type,engine_bundle_key,repository_set_hash,reason,status,semantic_index_status) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,?,?,'DISABLED')",
                command.workflowRunId(), version, bundleId, command.engineType(), engineBundleKey, bundleHash, command.reason(), status);
        return jdbc.queryForObject("SELECT id FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND version_no=?", Long.class, command.workflowRunId(), version);
    }

    private void activateBinding(long runId, long bindingId, long bundleId) {
        lockWorkflow(runId);
        jdbc.update("UPDATE workflow_run_code_graph_binding SET status='SUPERSEDED',time_updated=NOW(3) WHERE workflow_run_id=? AND status='ACTIVE' AND id<>?", runId, bindingId);
        jdbc.update("UPDATE workflow_run_code_graph_binding SET status='ACTIVE',bundle_id=?,activated_at=NOW(3),time_updated=NOW(3) WHERE id=? AND status='PREPARING'", bundleId, bindingId);
    }

    /**
     * M7: emits {@code code_graph.binding.activated} after a REPO_APPEND binding swap.
     * Initial-preparation swaps do not re-emit (they already have bundle.ready semantics).
     */
    private void publishBindingActivated(long runId, long bindingId, long bundleId, String jobType) {
        if (!"REPO_APPEND".equals(jobType)) return;
        try {
            var previous = jdbc.query("SELECT id,version_no,bundle_id FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND status='SUPERSEDED' ORDER BY time_updated DESC LIMIT 1",
                    (rs, n) -> Map.<String, Object>of(
                            "bindingId", rs.getLong(1),
                            "version", rs.getInt(2),
                            "bundleId", rs.getLong(3)),
                    runId);
            var current = jdbc.queryForObject("SELECT version_no,repository_set_hash FROM workflow_run_code_graph_binding WHERE id=?",
                    (rs, n) -> Map.<String, Object>of(
                            "version", rs.getInt(1),
                            "repositorySetHash", rs.getString(2)),
                    bindingId);
            var repositoryCount = jdbc.queryForObject("SELECT COUNT(*) FROM code_graph_bundle_repository WHERE bundle_id=?", Integer.class, bundleId);
            var payload = new HashMap<String, Object>();
            payload.put("bindingId", bindingId);
            payload.put("bundleId", bundleId);
            payload.put("version", current == null ? null : current.get("version"));
            payload.put("repositoryCount", repositoryCount == null ? 0 : repositoryCount);
            if (!previous.isEmpty()) {
                payload.put("previousBindingId", previous.get(0).get("bindingId"));
                payload.put("previousVersion", previous.get(0).get("version"));
                payload.put("previousBundleId", previous.get(0).get("bundleId"));
            }
            lifecycle.event(runId, "code_graph.binding.activated", payload);
        } catch (RuntimeException exception) {
            // Event emission must never roll back the binding swap.
        }
    }

    private void markWorkflowReady(long runId) {
        jdbc.update("UPDATE workflow_run SET status='READY_TO_START',failure_reason=NULL,time_updated=NOW(3) WHERE id=? AND status='PREPARING_CODE_GRAPH'", runId);
    }

    /**
     * M7: workflow status transitions only on the INITIAL preparation path. A successful
     * REPO_APPEND updates the binding/bundle/job in place; the running workflow keeps
     * its current status (typically RUNNING) so existing agent runs are undisturbed.
     */
    private void markWorkflowReadyIfInitial(String jobType, long runId) {
        if ("REPO_APPEND".equals(jobType)) return;
        markWorkflowReady(runId);
    }

    private String jobType(long jobId) {
        var rows = jdbc.query("SELECT job_type FROM code_graph_generation_job WHERE id=?", (rs, n) -> rs.getString(1), jobId);
        return rows.isEmpty() ? "INITIAL" : rows.get(0);
    }

    private void lockWorkflow(long workflowRunId) {
        jdbc.queryForObject("SELECT id FROM workflow_run WHERE id=? FOR UPDATE", Long.class, workflowRunId);
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

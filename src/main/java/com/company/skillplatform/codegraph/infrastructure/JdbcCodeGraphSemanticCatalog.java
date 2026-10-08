package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.application.CodeGraphSemanticCatalog;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort.GraphRef;
import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort.GraphRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Repository
public class JdbcCodeGraphSemanticCatalog implements CodeGraphSemanticCatalog {
    private final JdbcTemplate jdbc;
    public JdbcCodeGraphSemanticCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<IndexTarget> findByGenerationJob(long jobId) {
        var rows = jdbc.query("SELECT b.id,b.bundle_id,g.artifact_key,g.artifact_sha256 FROM code_graph_generation_job j JOIN workflow_run_code_graph_binding b ON b.id=j.target_binding_id JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE j.id=? AND j.status='READY' AND b.status IN ('ACTIVE','SUPERSEDED') AND g.status='READY'",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)}, jobId);
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0); var bundleId = (Long) row[1];
        var repositories = jdbc.query("SELECT s.id,br.repository_alias,s.logical_repository_key,s.commit_sha FROM code_graph_bundle_repository br JOIN code_graph_repository_snapshot s ON s.id=br.repository_snapshot_id WHERE br.bundle_id=? ORDER BY br.repository_alias",
                (rs, n) -> new RepositorySnapshot(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), bundleId);
        var graphRepositories = repositories.stream().map(r -> new GraphRepository(r.alias(), r.alias())).toList();
        return Optional.of(new IndexTarget((Long) row[0], bundleId,
                new GraphRef((String) row[2], (String) row[3], graphRepositories), repositories));
    }

    @Override
    public void updateStatus(long bindingId, String status, String errorCode) {
        jdbc.update("UPDATE workflow_run_code_graph_binding SET semantic_index_status=?,semantic_index_error_code=?,time_updated=NOW(3) WHERE id=?",
                status, errorCode, bindingId);
    }

    @Override
    public boolean tryMarkIndexing(long bindingId) {
        int updated = jdbc.update("UPDATE workflow_run_code_graph_binding SET semantic_index_status='INDEXING',semantic_index_error_code=NULL,time_updated=NOW(3) WHERE id=? AND (semantic_index_status IS NULL OR semantic_index_status <> 'INDEXING')",
                bindingId);
        return updated == 1;
    }

    @Override
    public Optional<String> currentStatus(long bindingId) {
        var rows = jdbc.query("SELECT semantic_index_status FROM workflow_run_code_graph_binding WHERE id=?",
                (rs, n) -> rs.getString(1), bindingId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    @Override
    public void markDegradedIfReady(long bindingId, String errorCode) {
        jdbc.update("UPDATE workflow_run_code_graph_binding SET semantic_index_status='DEGRADED',semantic_index_error_code=?,time_updated=NOW(3) WHERE id=? AND semantic_index_status='READY'",
                errorCode, bindingId);
    }

    @Override
    public List<Long> findStaleIndexing(int staleBeforeMinutes) {
        return jdbc.query("SELECT id FROM workflow_run_code_graph_binding WHERE semantic_index_status='INDEXING' AND time_updated < DATE_SUB(NOW(3), INTERVAL ? MINUTE)",
                (rs, n) -> rs.getLong(1), staleBeforeMinutes);
    }

    @Override
    public OptionalLong findLatestReadyJobForBinding(long bindingId) {
        var rows = jdbc.query("SELECT id FROM code_graph_generation_job WHERE target_binding_id=? AND status='READY' ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getLong(1), bindingId);
        return rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.get(0));
    }

    @Override
    public List<Long> findDisabledReadyBindings() {
        return jdbc.query("SELECT b.id FROM workflow_run_code_graph_binding b JOIN code_graph_bundle g ON g.id=b.bundle_id WHERE b.semantic_index_status='DISABLED' AND b.status IN ('ACTIVE','SUPERSEDED') AND g.status='READY' ORDER BY b.id",
                (rs, n) -> rs.getLong(1));
    }
}

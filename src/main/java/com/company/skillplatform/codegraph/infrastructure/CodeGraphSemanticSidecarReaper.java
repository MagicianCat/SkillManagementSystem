package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M7 §4: garbage-collects the Qdrant points of SUPERSEDED code graph bundles.
 *
 * <p>Safety contract: a bundle's semantic points are deleted only when no non-terminal
 * agent run still pins the SUPERSEDED binding (i.e. no row in {@code agent_workflow_run}
 * with that {@code code_graph_binding_id} and a status in QUEUED / STARTING / RUNNING /
 * WAITING_HUMAN / PAUSED). Bundle artifacts and repository snapshots are kept as reuse
 * cache; only the vector points are dropped.</p>
 *
 * <p>The sweep is idempotent: re-running it after a crash or a partial delete is safe,
 * because {@code deleteBundle} on an empty collection is a no-op on the vector store.
 * Failures are logged and retried on the next tick — they never propagate.</p>
 */
@Component
public class CodeGraphSemanticSidecarReaper {
    private static final Logger log = LoggerFactory.getLogger(CodeGraphSemanticSidecarReaper.class);
    private static final List<String> NON_TERMINAL_AGENT_STATUSES =
            List.of("QUEUED", "STARTING", "RUNNING", "WAITING_HUMAN", "PAUSED");

    private final JdbcTemplate jdbc;
    private final CodeGraphVectorStorePort vectorStore;

    public CodeGraphSemanticSidecarReaper(JdbcTemplate jdbc, CodeGraphVectorStorePort vectorStore) {
        this.jdbc = jdbc;
        this.vectorStore = vectorStore;
    }

    @Scheduled(fixedDelayString = "${skill-platform.code-graph.semantic-reaper-interval:PT5M}")
    public void sweep() {
        try {
            var candidates = findReapableBundles();
            for (var candidate : candidates) {
                reapOne(candidate);
            }
        } catch (RuntimeException exception) {
            log.warn("event=code_graph.semantic_reaper.sweep_failed err={}", exception.toString());
        }
    }

    /** Bundle ids whose owning binding is SUPERSEDED and which no live agent run references. */
    private List<Long> findReapableBundles() {
        var placeholders = String.join(",", NON_TERMINAL_AGENT_STATUSES.stream().map(s -> "?").toList());
        var sql = "SELECT DISTINCT b.bundle_id FROM workflow_run_code_graph_binding b JOIN code_graph_bundle g ON g.id=b.bundle_id " +
                "WHERE b.status='SUPERSEDED' AND b.bundle_id IS NOT NULL " +
                "AND g.semantic_cleanup_status='PENDING' " +
                "AND NOT EXISTS (SELECT 1 FROM workflow_run_code_graph_binding live WHERE live.bundle_id=b.bundle_id AND live.status IN ('ACTIVE','PREPARING')) " +
                "AND NOT EXISTS (SELECT 1 FROM workflow_run_code_graph_binding indexing WHERE indexing.bundle_id=b.bundle_id AND indexing.semantic_index_status='INDEXING') " +
                "AND NOT EXISTS (SELECT 1 FROM agent_workflow_run ar WHERE ar.code_graph_binding_id=b.id " +
                "  AND ar.status IN (" + placeholders + ")) " +
                "LIMIT 100";
        var params = new java.util.ArrayList<>();
        params.addAll(NON_TERMINAL_AGENT_STATUSES);
        return jdbc.query(sql, (rs, n) -> rs.getLong(1), params.toArray());
    }

    /** Deletes one bundle's points. Failures are logged; the next tick retries. */
    public void reapOne(long bundleId) {
        if (!claim(bundleId)) return;
        try {
            vectorStore.deleteBundle(bundleId);
            jdbc.update("UPDATE code_graph_bundle SET semantic_cleanup_status='DELETED',semantic_points_deleted_at=NOW(3),time_updated=NOW(3) WHERE id=? AND semantic_cleanup_status='DELETING'", bundleId);
            log.info("event=code_graph.semantic_reaper.deleted bundleId={}", bundleId);
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE code_graph_bundle SET semantic_cleanup_status='PENDING',time_updated=NOW(3) WHERE id=? AND semantic_cleanup_status='DELETING'", bundleId);
            // Vector store delete must be idempotent and retryable; never throw into the scheduler.
            log.warn("event=code_graph.semantic_reaper.delete_failed bundleId={} err={}", bundleId, exception.toString());
        }
    }

    boolean claim(long bundleId) {
        return jdbc.update("UPDATE code_graph_bundle g SET g.semantic_cleanup_status='DELETING',g.time_updated=NOW(3) WHERE g.id=? AND g.semantic_cleanup_status='PENDING' AND NOT EXISTS (SELECT 1 FROM workflow_run_code_graph_binding b WHERE b.bundle_id=g.id AND b.status IN ('ACTIVE','PREPARING')) AND NOT EXISTS (SELECT 1 FROM workflow_run_code_graph_binding b WHERE b.bundle_id=g.id AND b.semantic_index_status='INDEXING')", bundleId) == 1;
    }
}

package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeRoleMetadataReconcileService {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ExternalWikiRoleResolver resolver;
    private final KnowledgeRoleMetadataSync sync;
    private final VectorStorePort vectors;
    private final KnowledgeProperties properties;
    private final boolean enabled;
    private final int pageSize;
    private final long intervalMs;

    public KnowledgeRoleMetadataReconcileService(JdbcTemplate db, TransactionTemplate transactions,
            ExternalWikiRoleResolver resolver, KnowledgeRoleMetadataSync sync, VectorStorePort vectors,
            KnowledgeProperties properties, @Value("${knowledge.role-metadata.enabled:true}") boolean enabled,
            @Value("${knowledge.role-metadata.reconcile-page-size:200}") int pageSize,
            @Value("${knowledge.role-metadata.reconcile-interval-ms:600000}") long intervalMs) {
        this.db=db; this.transactions=transactions; this.resolver=resolver; this.sync=sync;
        this.vectors=vectors; this.properties=properties; this.enabled=enabled;
        this.pageSize=Math.max(1,pageSize); this.intervalMs=Math.max(1000,intervalMs);
    }

    public long start(Long operator) {
        db.update("""
                INSERT INTO knowledge_role_metadata_scan(status, high_watermark, operator_id)
                SELECT 'RUNNING',COALESCE(MAX(id),0),? FROM wiki_document
                ON DUPLICATE KEY UPDATE id=knowledge_role_metadata_scan.id
                """, operator);
        return db.queryForObject("SELECT MAX(id) FROM knowledge_role_metadata_scan", Long.class);
    }

    @Scheduled(fixedDelayString="${knowledge.role-metadata.scan-poll-ms:5000}")
    public void scanPage() {
        if (!enabled || !properties.isEnabled()) return;
        if (db.queryForObject("""
                SELECT COUNT(*) FROM knowledge_role_metadata_scan
                WHERE status='RUNNING' OR updated_at>=TIMESTAMPADD(MICROSECOND,?,NOW(3))
                """, Integer.class, -intervalMs*1000) == 0) start(null);
        var scans = db.query("""
                SELECT id,cursor_id,high_watermark FROM knowledge_role_metadata_scan WHERE status='RUNNING'
                """, (rs, row) -> new Scan(rs.getLong(1),rs.getLong(2),rs.getLong(3)));
        if (scans.isEmpty()) return;
        Scan scan = scans.get(0);
        List<Long> ids = db.query("""
                SELECT id FROM wiki_document WHERE id>? AND id<=? AND status='ACTIVE' ORDER BY id LIMIT ?
                """, (rs,row) -> rs.getLong(1),scan.cursor(),scan.highWatermark(),pageSize);
        var metadata = resolver.resolveAll(ids);
        List<Long> stale = new ArrayList<>();
        int verified=0, waiting=0;
        for (long id : ids) {
            try {
                if (vectors.hasStaleRoleMetadata(id,metadata.get(id))) stale.add(id);
                else if (vectors.documentState(id)!=null) verified++;
                else if (sync.expectsIndex(id)) waiting++;
            } catch (RuntimeException failure) {
                db.update("UPDATE knowledge_role_metadata_scan SET failed=failed+1 WHERE id=? AND status='RUNNING' AND cursor_id=?",
                        scan.id(),scan.cursor());
                throw failure;
            }
        }
        int verifiedCount=verified, waitingCount=waiting;
        long cursor=ids.isEmpty()?scan.highWatermark():ids.get(ids.size()-1);
        transactions.executeWithoutResult(tx -> {
            int claimed=db.update("""
                    UPDATE knowledge_role_metadata_scan SET cursor_id=?, scanned=scanned+?, verified=verified+?,
                        queued=queued+?, waiting_index=waiting_index+?, status=?
                    WHERE id=? AND status='RUNNING' AND cursor_id=?
                    """,cursor,ids.size(),verifiedCount,stale.size(),waitingCount,
                    ids.size()<pageSize?"COMPLETED":"RUNNING",scan.id(),scan.cursor());
            if (claimed==1) stale.forEach(sync::enqueueIfNeeded);
        });
    }

    public Map<String,Object> status() {
        var scans = db.queryForList("SELECT * FROM knowledge_role_metadata_scan ORDER BY id DESC LIMIT 1");
        var queue = db.queryForList("SELECT status,COUNT(*) count FROM knowledge_role_metadata_sync GROUP BY status");
        var success = db.queryForMap("SELECT MAX(updated_at) lastSuccessAt FROM knowledge_role_metadata_sync WHERE status='SUCCEEDED'");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("scan", scans.isEmpty()?null:scans.get(0));
        result.put("queue",queue);
        result.put("lastSuccessAt",success.get("lastSuccessAt"));
        return result;
    }

    private record Scan(long id, long cursor, long highWatermark) {}
}

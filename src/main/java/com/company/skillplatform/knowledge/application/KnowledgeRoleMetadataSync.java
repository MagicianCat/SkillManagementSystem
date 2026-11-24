package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRoleMetadataSync {
    private static final String UPSERT = """
             ON DUPLICATE KEY UPDATE desired_generation=desired_generation+1,
             attempts=0,last_error_code=NULL,next_attempt_at=NOW(3),
             status=IF(knowledge_role_metadata_sync.status='RUNNING','RUNNING','PENDING')
            """;
    private final JdbcTemplate db;
    private final VectorStorePort vectors;
    private final ExternalWikiRoleResolver resolver;
    private final KnowledgeProperties properties;
    private final boolean enabled;
    private final int batchSize;

    public KnowledgeRoleMetadataSync(JdbcTemplate db, VectorStorePort vectors, ExternalWikiRoleResolver resolver,
            KnowledgeProperties properties, @Value("${knowledge.role-metadata.enabled:true}") boolean enabled,
            @Value("${knowledge.role-metadata.sync-batch-size:20}") int batchSize) {
        this.db=db; this.vectors=vectors; this.resolver=resolver; this.properties=properties;
        this.enabled=enabled; this.batchSize=Math.max(1, batchSize);
    }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void requestRepair(long id) {
        if(enabled) enqueueIfNeeded(id);
    }

    void enqueueIfNeeded(long id) {
        db.update("""
                INSERT INTO knowledge_role_metadata_sync(document_id) VALUES (?)
                ON DUPLICATE KEY UPDATE
                  desired_generation=desired_generation+IF(status='SUCCEEDED',1,0),
                  attempts=IF(status='SUCCEEDED',0,attempts),
                  next_attempt_at=IF(status='SUCCEEDED',NOW(3),next_attempt_at),
                  status=IF(status='SUCCEEDED','PENDING',status)
                """,id);
    }

    public void enqueueForResource(long resourceId) { enqueueMatching("r.id=?", resourceId); }

    public void enqueueForRole(String source, String externalRoleId) {
        enqueueMatching("""
                EXISTS (SELECT 1 FROM external_role_resource rr JOIN external_role er ON er.id=rr.role_id
                        WHERE rr.resource_id=r.id AND er.source_system=? AND er.external_role_id=?)
                """, source, externalRoleId);
    }

    private void enqueueMatching(String filter, Object... args) {
        db.update("INSERT INTO knowledge_role_metadata_sync(document_id) SELECT DISTINCT d.id "
                + "FROM external_resource r JOIN wiki_document d ON " + ExternalWikiRoleResolver.SOURCE_MATCH
                + " WHERE d.status='ACTIVE' AND " + filter + UPSERT, args);
    }

    @Scheduled(fixedDelayString="${knowledge.role-metadata.sync-poll-ms:3000}")
    public void poll() {
        if (!enabled || !properties.isEnabled()) return;
        var ids = db.query("""
                SELECT document_id FROM knowledge_role_metadata_sync
                WHERE (status IN ('PENDING','WAIT_INDEX','FAILED_RETRY') AND next_attempt_at<=NOW(3))
                   OR (status='RUNNING' AND lease_until<=NOW(3))
                ORDER BY next_attempt_at, document_id LIMIT ?
                """, (rs, row) -> rs.getLong(1), batchSize);
        for (long id : ids) process(id);
    }

    private void process(long id) {
        String token = UUID.randomUUID().toString();
        if (db.update("""
                UPDATE knowledge_role_metadata_sync SET status='RUNNING',lease_token=?,
                    lease_until=DATE_ADD(NOW(3),INTERVAL 2 MINUTE)
                WHERE document_id=? AND ((status IN ('PENDING','WAIT_INDEX','FAILED_RETRY') AND next_attempt_at<=NOW(3))
                    OR (status='RUNNING' AND lease_until<=NOW(3)))
                """, token, id) != 1) return;
        var claims = db.query("""
                SELECT desired_generation,attempts FROM knowledge_role_metadata_sync WHERE document_id=? AND lease_token=?
                """, (rs, row) -> new Claim(rs.getLong(1), rs.getInt(2)), id, token);
        if (claims.isEmpty()) return;
        Claim claim = claims.get(0);
        String status;
        String error = null;
        int attempts = claim.attempts();
        int delay = 0;
        try {
            boolean indexed = vectors.syncExternalRoleMetadata(id, () -> resolver.resolve(id));
            status = indexed || !expectsIndex(id) ? "SUCCEEDED" : "WAIT_INDEX";
            delay = indexed ? 0 : 600;
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName();
            attempts++;
            status = attempts >= 10 ? "EXHAUSTED" : "FAILED_RETRY";
            delay = Math.min(60, 1 << Math.min(attempts-1, 6)) * 60;
            LoggerFactory.getLogger(getClass()).warn("event=knowledge.role_metadata.failed documentId={} attempts={} status={}", id, attempts, status, failure);
        }
        db.update("""
                UPDATE knowledge_role_metadata_sync SET
                    applied_generation=IF(desired_generation=? AND ?='SUCCEEDED',desired_generation,applied_generation),
                    status=IF(desired_generation=?,?,'PENDING'),
                    attempts=IF(desired_generation=?, ?,0),
                    last_error_code=IF(desired_generation=?, ?,NULL),
                    next_attempt_at=TIMESTAMPADD(SECOND,IF(desired_generation=?, ?,0),NOW(3)),
                    lease_token=NULL,lease_until=NULL
                WHERE document_id=? AND lease_token=?
                """, claim.generation(),status,claim.generation(),status,claim.generation(),attempts,
                claim.generation(),error,claim.generation(),delay,id,token);
    }

    boolean expectsIndex(long id) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wiki_document d WHERE id=? AND status='ACTIVE'
                  AND current_revision_id IS NOT NULL AND (team_id IS NOT NULL OR
                    (platform_visible=1 AND EXISTS(SELECT 1 FROM wiki_platform_snapshot ps WHERE ps.document_id=d.id))))
                """, Boolean.class, id));
    }

    public int retry() {
        return db.update("""
                UPDATE knowledge_role_metadata_sync SET status='PENDING',attempts=0,last_error_code=NULL,next_attempt_at=NOW(3)
                WHERE status IN ('FAILED_RETRY','EXHAUSTED')
                """);
    }

    private record Claim(long generation, int attempts) {}
}

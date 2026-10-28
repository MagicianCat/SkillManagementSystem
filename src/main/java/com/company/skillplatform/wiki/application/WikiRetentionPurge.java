package com.company.skillplatform.wiki.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Permanently removes Wiki documents 180 days after archival, starting with leaf nodes. */
@Component
public class WikiRetentionPurge {
    private static final Logger log = LoggerFactory.getLogger(WikiRetentionPurge.class);
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public WikiRetentionPurge(JdbcTemplate db, TransactionTemplate transactions) {
        this.db = db; this.transactions = transactions;
    }

    @Scheduled(cron = "${wiki.retention.purge-cron:0 20 3 * * *}")
    public void purge() {
        int removed;
        do {
            removed = 0;
            long afterId = 0;
            while (true) {
                List<Long> ids = db.query("SELECT d.id FROM wiki_document d WHERE d.id>? AND d.status='ARCHIVED' " +
                        "AND d.deleted_at < DATE_SUB(NOW(3), INTERVAL 180 DAY) " +
                        "AND NOT EXISTS (SELECT 1 FROM wiki_document child WHERE child.parent_id=d.id) " +
                        "ORDER BY d.id LIMIT 100", (rs, row) -> rs.getLong(1), afterId);
                if (ids.isEmpty()) break;
                for (Long id : ids) {
                    afterId = id;
                    try {
                        transactions.executeWithoutResult(status -> purgeOne(id));
                        removed++;
                    } catch (RuntimeException ex) {
                        log.error("event=wiki.retention_purge.failed documentId={}", id, ex);
                    }
                }
            }
        } while (removed > 0);
    }

    private void purgeOne(Long id) {
        List<Long> eligible = db.query("SELECT d.id FROM wiki_document d WHERE d.id=? AND d.status='ARCHIVED' " +
                "AND d.deleted_at < DATE_SUB(NOW(3), INTERVAL 180 DAY) " +
                "AND NOT EXISTS (SELECT 1 FROM wiki_document child WHERE child.parent_id=d.id) FOR UPDATE", (rs, row) -> rs.getLong(1), id);
        if (eligible.isEmpty()) return;
        db.update("DELETE FROM skill_review_wiki_revision WHERE document_id=?", id);
        db.update("DELETE FROM wiki_document_review WHERE document_id=?", id);
        db.update("DELETE FROM document_agent_session_wiki_context WHERE wiki_document_id=?", id);
        db.update("DELETE FROM document_agent_job_context WHERE wiki_document_id=?", id);
        db.update("DELETE FROM project_agent_configuration_context WHERE wiki_document_id=?", id);
        db.update("DELETE FROM workflow_run_context_snapshot WHERE wiki_document_id=?", id);
        db.update("DELETE FROM external_import_record WHERE target_type='WIKI' AND target_id=?", id);
        db.update("UPDATE wiki_document SET current_revision_id=NULL WHERE id=?", id);
        db.update("DELETE FROM wiki_document WHERE id=?", id);
        log.info("event=wiki.retention_purge.completed documentId={}", id);
    }
}

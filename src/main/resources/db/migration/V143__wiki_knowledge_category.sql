ALTER TABLE wiki_document ADD COLUMN knowledge_category VARCHAR(32) NOT NULL DEFAULT 'OTHER';
ALTER TABLE wiki_platform_snapshot ADD COLUMN approved_knowledge_category VARCHAR(32) NOT NULL DEFAULT 'OTHER';
ALTER TABLE wiki_platform_review_item ADD COLUMN knowledge_category_snapshot VARCHAR(32) NOT NULL DEFAULT 'OTHER';
CREATE INDEX idx_wiki_team_category_status ON wiki_document(team_id, knowledge_category, status);

-- Pre-upgrade reviews have fingerprints without category data; require a fresh submission.
UPDATE wiki_document_review SET status = 'STALE' WHERE status = 'PENDING';
DELETE FROM wiki_active_review_lock WHERE review_id IN (SELECT id FROM wiki_document_review WHERE status = 'STALE');
DELETE FROM wiki_active_skill_review_lock WHERE review_id IN (SELECT id FROM wiki_document_review WHERE status = 'STALE');

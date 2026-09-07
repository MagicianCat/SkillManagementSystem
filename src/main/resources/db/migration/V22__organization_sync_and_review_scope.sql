ALTER TABLE org_team ADD COLUMN last_synced_at DATETIME(3) NULL;
ALTER TABLE org_team ADD COLUMN external_parent_department_id VARCHAR(128) NULL;
ALTER TABLE iam_user ADD COLUMN avatar_url VARCHAR(512) NULL;
ALTER TABLE skill_review ADD COLUMN review_scope VARCHAR(32) NOT NULL DEFAULT 'PLATFORM';
ALTER TABLE skill_review ADD COLUMN parent_review_id BIGINT NULL;
ALTER TABLE skill_review ADD CONSTRAINT fk_review_parent FOREIGN KEY (parent_review_id) REFERENCES skill_review(id) ON DELETE RESTRICT;
ALTER TABLE skill_review ADD INDEX idx_review_scope_status (review_scope, status, submitted_at);

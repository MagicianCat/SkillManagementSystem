ALTER TABLE wiki_document_review
  ADD COLUMN source_team_id BIGINT NULL,
  ADD COLUMN tree_fingerprint CHAR(64) NULL,
  ADD COLUMN wiki_count INT NULL,
  ADD COLUMN skill_count INT NULL,
  ADD INDEX idx_wiki_review_source_status (source_team_id, status);

CREATE TABLE wiki_platform_review_item (
  review_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  revision_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  title_snapshot VARCHAR(255) NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  hierarchy_path VARCHAR(2048) NOT NULL,
  PRIMARY KEY (review_id, document_id),
  INDEX idx_wiki_review_item_document (document_id, review_id)
);

CREATE TABLE wiki_platform_review_skill (
  review_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  published_version_id BIGINT NOT NULL,
  scope_at_submit VARCHAR(32) NOT NULL,
  source_team_id BIGINT NULL,
  PRIMARY KEY (review_id, skill_id),
  INDEX idx_wiki_review_skill_skill (skill_id, review_id)
);

CREATE TABLE wiki_active_review_lock (
  document_id BIGINT NOT NULL PRIMARY KEY,
  review_id BIGINT NOT NULL,
  INDEX idx_wiki_active_review (review_id)
);

INSERT INTO wiki_active_review_lock(document_id,review_id)
SELECT document_id, MAX(id) FROM wiki_document_review
WHERE status = 'PENDING' GROUP BY document_id;

CREATE TABLE wiki_platform_snapshot (
  document_id BIGINT NOT NULL PRIMARY KEY,
  approved_revision_id BIGINT NOT NULL,
  approved_title VARCHAR(255) NOT NULL,
  approved_parent_id BIGINT NULL,
  approved_review_id BIGINT NULL,
  source_team_id BIGINT NULL,
  published_at DATETIME(6) NOT NULL,
  published_by BIGINT NULL
);

CREATE TABLE wiki_platform_skill_link (
  document_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  published_skill_version_id BIGINT NOT NULL,
  approved_review_id BIGINT NULL,
  published_at DATETIME(6) NOT NULL,
  PRIMARY KEY (document_id, skill_id),
  INDEX idx_wiki_platform_link_skill (skill_id)
);

ALTER TABLE skill
  ADD COLUMN origin_team_id BIGINT NULL,
  ADD COLUMN latest_platform_approved_version_id BIGINT NULL;

UPDATE skill SET origin_team_id = team_id WHERE team_id IS NOT NULL;
UPDATE skill SET latest_platform_approved_version_id = latest_published_version_id
WHERE scope_type = 'PLATFORM' AND latest_published_version_id IS NOT NULL;

-- Only an approved revision can seed a legacy team Wiki's public content.
-- If its team revision changed after approval, the original title is unknowable;
-- use a neutral title until the team reviews it again.
INSERT INTO wiki_platform_snapshot
  (document_id, approved_revision_id, approved_title, approved_parent_id,
   approved_review_id, source_team_id, published_at, published_by)
SELECT d.id, r.revision_id,
       CASE WHEN d.current_revision_id = r.revision_id THEN d.title ELSE CONCAT('历史 Wiki ', d.id) END,
       d.parent_id, r.id, d.team_id, r.reviewed_at, r.reviewer_id
FROM wiki_document d
JOIN wiki_document_review r ON r.id = (
  SELECT x.id FROM wiki_document_review x
  WHERE x.document_id = d.id AND x.status = 'APPROVED'
  ORDER BY x.reviewed_at DESC, x.id DESC LIMIT 1)
WHERE d.status = 'ACTIVE' AND d.platform_visible = 1 AND d.team_id IS NOT NULL;

INSERT INTO wiki_platform_snapshot
  (document_id, approved_revision_id, approved_title, approved_parent_id,
   approved_review_id, source_team_id, published_at, published_by)
SELECT d.id, d.current_revision_id, d.title, d.parent_id, NULL, NULL,
       CURRENT_TIMESTAMP(6), d.updated_by
FROM wiki_document d
WHERE d.status = 'ACTIVE' AND d.platform_visible = 1
  AND d.team_id IS NULL AND d.current_revision_id IS NOT NULL;

-- Backfill links only for documents whose current team revision is the approved one.
INSERT INTO wiki_platform_skill_link
  (document_id, skill_id, published_skill_version_id, approved_review_id, published_at)
SELECT l.document_id, l.skill_id, s.latest_platform_approved_version_id,
       p.approved_review_id, p.published_at
FROM wiki_document_skill l
JOIN wiki_platform_snapshot p ON p.document_id = l.document_id
JOIN wiki_document d ON d.id = l.document_id
JOIN skill s ON s.id = l.skill_id
WHERE (d.team_id IS NULL OR d.current_revision_id = p.approved_revision_id)
  AND s.scope_type = 'PLATFORM'
  AND s.latest_platform_approved_version_id IS NOT NULL;

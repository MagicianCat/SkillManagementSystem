CREATE TABLE wiki_category_batch (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  status VARCHAR(32) NOT NULL,
  initiated_by BIGINT NOT NULL,
  include_confirmed_other BOOLEAN NOT NULL DEFAULT FALSE,
  candidate_count INT NOT NULL DEFAULT 0,
  succeeded_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  skipped_count INT NOT NULL DEFAULT 0,
  reviewed_count INT NOT NULL DEFAULT 0,
  model_key VARCHAR(128),
  prompt_version VARCHAR(64) NOT NULL,
  started_at DATETIME(3),
  finished_at DATETIME(3),
  notification_sent_at DATETIME(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_wiki_category_batch_status (status)
);
CREATE TABLE wiki_category_batch_guard (
  id TINYINT NOT NULL PRIMARY KEY,
  active_batch_id BIGINT,
  CONSTRAINT fk_wiki_category_guard_batch FOREIGN KEY (active_batch_id) REFERENCES wiki_category_batch(id)
);
INSERT INTO wiki_category_batch_guard(id) VALUES(1);
CREATE TABLE wiki_category_batch_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  batch_id BIGINT NOT NULL,
  wiki_document_id BIGINT NOT NULL,
  team_id BIGINT,
  source_revision_id BIGINT NOT NULL,
  source_version_no INT NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  input_fingerprint CHAR(64) NOT NULL,
  suggested_category VARCHAR(32),
  selected_probability DOUBLE,
  probabilities_json TEXT,
  model_key VARCHAR(128),
  prompt_version VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  error_code VARCHAR(128),
  review_category VARCHAR(32),
  review_action VARCHAR(32),
  reviewer_id BIGINT,
  reviewed_at DATETIME(3),
  applied_at DATETIME(3),
  lease_owner VARCHAR(128),
  lease_until DATETIME(3),
  next_attempt_at DATETIME(3),
  index_status VARCHAR(16),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_wiki_category_batch_document (batch_id, wiki_document_id),
  KEY idx_wiki_category_item_claim (status, lease_until),
  CONSTRAINT fk_wiki_category_item_batch FOREIGN KEY (batch_id) REFERENCES wiki_category_batch(id)
);
CREATE TABLE wiki_category_confirmation (
  wiki_document_id BIGINT NOT NULL PRIMARY KEY,
  confirmed_category VARCHAR(32) NOT NULL,
  confirmed_input_fingerprint CHAR(64) NOT NULL,
  confirmed_revision_id BIGINT NOT NULL,
  confirmed_by BIGINT NOT NULL,
  confirmation_source VARCHAR(32) NOT NULL,
  confirmed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

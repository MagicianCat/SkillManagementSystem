ALTER TABLE collab_group
  ADD COLUMN asdm_repository_url VARCHAR(2048) NULL,
  ADD COLUMN asdm_gitlab_project_id BIGINT NULL;

ALTER TABLE collab_project
  ADD COLUMN asdm_branch VARCHAR(255) COLLATE utf8mb4_bin NULL,
  ADD COLUMN asdm_requirement_path VARCHAR(1024) NULL,
  ADD COLUMN asdm_binding_status VARCHAR(32) NOT NULL DEFAULT 'DISABLED',
  ADD COLUMN asdm_baseline_head_sha CHAR(40) NULL,
  ADD COLUMN asdm_last_scanned_head_sha CHAR(40) NULL,
  ADD COLUMN asdm_last_scan_at DATETIME(3) NULL,
  ADD UNIQUE KEY uk_collab_req_group_asdm_branch(group_id, asdm_branch),
  ADD INDEX idx_collab_req_asdm_status(group_id, asdm_binding_status, status);

CREATE TABLE collab_asdm_credential (
  group_id BIGINT NOT NULL PRIMARY KEY,
  token_ciphertext TEXT NOT NULL,
  encryption_key_version INT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'VALID',
  last_validated_at DATETIME(3) NULL,
  expires_at DATETIME(3) NULL,
  last_error_code VARCHAR(80) NULL,
  failure_notified_at DATETIME(3) NULL,
  updated_by BIGINT NOT NULL,
  version_no INT NOT NULL DEFAULT 0,
  time_created DATETIME(3) NOT NULL,
  time_updated DATETIME(3) NOT NULL,
  CONSTRAINT fk_collab_asdm_cred_group FOREIGN KEY (group_id)
    REFERENCES collab_group(id) ON DELETE RESTRICT,
  CONSTRAINT fk_collab_asdm_cred_updater FOREIGN KEY (updated_by)
    REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE collab_asdm_candidate (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  requirement_id BIGINT NOT NULL,
  stage_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  source_path VARCHAR(1200) NOT NULL,
  source_commit_sha CHAR(40) NOT NULL,
  source_blob_sha CHAR(40) NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  markdown_snapshot MEDIUMTEXT NULL,
  snapshot_cached_at DATETIME(3) NULL,
  detected_revision INT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'DEBOUNCING',
  first_detected_at DATETIME(3) NOT NULL,
  last_changed_at DATETIME(3) NOT NULL,
  notify_after DATETIME(3) NOT NULL,
  notified_revision INT NULL,
  notified_at DATETIME(3) NULL,
  confirmed_by BIGINT NULL,
  confirmed_at DATETIME(3) NULL,
  submission_id BIGINT NULL,
  last_error_code VARCHAR(80) NULL,
  version_no INT NOT NULL DEFAULT 0,
  time_updated DATETIME(3) NOT NULL,
  CONSTRAINT uk_collab_asdm_candidate_attempt UNIQUE(stage_id,attempt_no),
  CONSTRAINT fk_collab_asdm_candidate_requirement FOREIGN KEY (requirement_id)
    REFERENCES collab_project(id) ON DELETE RESTRICT,
  CONSTRAINT fk_collab_asdm_candidate_stage FOREIGN KEY (stage_id)
    REFERENCES collab_stage(id) ON DELETE RESTRICT,
  CONSTRAINT fk_collab_asdm_candidate_submit FOREIGN KEY (submission_id)
    REFERENCES collab_submission(id) ON DELETE RESTRICT,
  CONSTRAINT fk_collab_asdm_candidate_confirm FOREIGN KEY (confirmed_by)
    REFERENCES iam_user(id) ON DELETE RESTRICT,
  INDEX idx_collab_asdm_candidate_due(status,notify_after),
  INDEX idx_collab_asdm_candidate_requirement(requirement_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE collab_artifact_version
  ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'SKILL',
  ADD COLUMN source_ref_json JSON NULL;

ALTER TABLE collab_submission
  ADD COLUMN publication_gate_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_DONE';

-- Token rotation and restart-safe recovery work items.
CREATE TABLE collab_asdm_scan_task (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  group_id BIGINT NOT NULL,
  requirement_id BIGINT NULL,
  task_type VARCHAR(32) NOT NULL,
  dedupe_key VARCHAR(255) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  available_at DATETIME(3) NOT NULL,
  claimed_at DATETIME(3) NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(80) NULL,
  time_created DATETIME(3) NOT NULL,
  time_updated DATETIME(3) NOT NULL,
  CONSTRAINT uk_collab_asdm_scan_task_dedupe UNIQUE(dedupe_key),
  CONSTRAINT fk_collab_asdm_scan_task_group FOREIGN KEY(group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
  CONSTRAINT fk_collab_asdm_scan_task_requirement FOREIGN KEY(requirement_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
  INDEX idx_collab_asdm_scan_task_ready(status,available_at),
  INDEX idx_collab_asdm_scan_task_group(group_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE collab_project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_key CHAR(36) NOT NULL,
    home_team_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NULL,
    owner_user_id BIGINT NOT NULL,
    approval_level VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    current_stage_order INT NOT NULL DEFAULT 1,
    version_no INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_project_key UNIQUE (project_key),
    CONSTRAINT fk_collab_project_team FOREIGN KEY (home_team_id) REFERENCES org_team(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_project_owner FOREIGN KEY (owner_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_project_creator FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_project_team (home_team_id, status),
    INDEX idx_collab_project_owner (owner_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_project_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    project_role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    joined_at DATETIME(3) NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_project_member UNIQUE (project_id,user_id),
    CONSTRAINT fk_collab_member_project FOREIGN KEY (project_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_member_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_member_updater FOREIGN KEY (updated_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_member_user (user_id,status,project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_stage_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    stage_key VARCHAR(48) NOT NULL,
    stage_order INT NOT NULL,
    title VARCHAR(128) NOT NULL,
    artifact_kind VARCHAR(64) NOT NULL,
    git_required BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_stage_definition_key UNIQUE (stage_key),
    CONSTRAINT uk_collab_stage_definition_order UNIQUE (stage_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO collab_stage_definition(stage_key,stage_order,title,artifact_kind,git_required,active,time_created,time_updated) VALUES
('REQUIREMENT',1,'需求分析','REQUIREMENT_DOCUMENT',FALSE,TRUE,NOW(3),NOW(3)),
('PRODUCT',2,'产品设计','PRODUCT_REQUIREMENTS',FALSE,TRUE,NOW(3),NOW(3)),
('ARCHITECTURE_DESIGN',3,'架构设计','ARCHITECTURE_DOCUMENT',FALSE,TRUE,NOW(3),NOW(3)),
('UI_DESIGN',4,'UI 设计','UI_DESIGN_DOCUMENT',FALSE,TRUE,NOW(3),NOW(3)),
('FRONTEND_CODING',5,'前端开发','FRONTEND_REPORT',TRUE,TRUE,NOW(3),NOW(3)),
('BACKEND_CODING',6,'后端开发','BACKEND_REPORT',TRUE,TRUE,NOW(3),NOW(3)),
('TESTING',7,'测试','TEST_REPORT',FALSE,TRUE,NOW(3),NOW(3)),
('ACCEPTANCE',8,'验收','ACCEPTANCE_REPORT',FALSE,TRUE,NOW(3),NOW(3));

CREATE TABLE collab_stage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    stage_key VARCHAR(48) NOT NULL,
    stage_order INT NOT NULL,
    title VARCHAR(128) NOT NULL,
    artifact_kind VARCHAR(64) NOT NULL,
    git_required BOOLEAN NOT NULL DEFAULT FALSE,
    definition_snapshot_json JSON NOT NULL,
    owner_user_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    attempt_no INT NOT NULL DEFAULT 1,
    version_no INT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_stage_project_key UNIQUE (project_id,stage_key),
    CONSTRAINT uk_collab_stage_project_order UNIQUE (project_id,stage_order),
    CONSTRAINT fk_collab_stage_project FOREIGN KEY (project_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_stage_owner FOREIGN KEY (owner_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_stage_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_stage_status (project_id,status,stage_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    stage_id BIGINT NOT NULL,
    kind VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    latest_version_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (id),
    CONSTRAINT fk_collab_artifact_project FOREIGN KEY (project_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_artifact_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    INDEX idx_collab_artifact_project_stage (project_id,stage_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_artifact_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    artifact_id BIGINT NOT NULL,
    stage_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    attempt_no INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    markdown_content MEDIUMTEXT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    git_evidence_json JSON NULL,
    upload_idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_artifact_version UNIQUE (artifact_id,version_no),
    CONSTRAINT uk_collab_stage_upload_idempotency UNIQUE (stage_id,upload_idempotency_key),
    CONSTRAINT fk_collab_artifact_version_artifact FOREIGN KEY (artifact_id) REFERENCES collab_artifact(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_artifact_version_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_artifact_version_user FOREIGN KEY (uploaded_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_artifact_version_stage (stage_id,attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE collab_artifact ADD CONSTRAINT fk_collab_artifact_latest_version FOREIGN KEY (latest_version_id) REFERENCES collab_artifact_version(id) ON DELETE RESTRICT;

CREATE TABLE collab_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    stage_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    artifact_version_id BIGINT NOT NULL,
    submitted_by BIGINT NOT NULL,
    policy_snapshot JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    submitted_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_submission_attempt UNIQUE (stage_id,attempt_no),
    CONSTRAINT uk_collab_submission_idempotency UNIQUE (stage_id,idempotency_key),
    CONSTRAINT fk_collab_submission_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_submission_artifact_version FOREIGN KEY (artifact_version_id) REFERENCES collab_artifact_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_submission_submitter FOREIGN KEY (submitted_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_submission_stage_status (stage_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_approval (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    submission_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    version_no INT NOT NULL DEFAULT 0,
    decision_comment VARCHAR(2000) NULL,
    decided_at DATETIME(3) NULL,
    action_nonce_hash CHAR(64) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_approval_submission UNIQUE (submission_id),
    CONSTRAINT fk_collab_approval_submission FOREIGN KEY (submission_id) REFERENCES collab_submission(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_approval_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_stage_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    stage_id BIGINT NULL,
    attempt_no INT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NULL,
    reference_id BIGINT NULL,
    payload_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_collab_stage_event_project FOREIGN KEY (project_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_stage_event_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_stage_event_actor FOREIGN KEY (actor_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_stage_event_project_time (project_id,created_at),
    INDEX idx_collab_stage_event_stage_attempt (stage_id,attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE external_import_record DROP INDEX uk_external_import_document;
CREATE INDEX ix_external_import_document ON external_import_record(resource_version_id, external_role_id, entry_path, target_type);

CREATE TABLE external_import_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    role_id BIGINT NOT NULL,
    team_id BIGINT NULL,
    parent_wiki_id BIGINT NULL,
    link_role_skills BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    operator_id BIGINT NOT NULL,
    snapshot_json JSON NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3) NULL,
    heartbeat_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    CONSTRAINT fk_external_import_job_role FOREIGN KEY (role_id) REFERENCES external_role(id),
    CONSTRAINT fk_external_import_job_operator FOREIGN KEY (operator_id) REFERENCES iam_user(id),
    INDEX ix_external_import_job_status (status, created_at)
);

CREATE TABLE external_import_job_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    entry_path VARCHAR(512) NULL,
    status VARCHAR(16) NOT NULL,
    target_id BIGINT NULL,
    error_code VARCHAR(128) NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_external_import_job_item_job FOREIGN KEY (job_id) REFERENCES external_import_job(id) ON DELETE CASCADE,
    INDEX ix_external_import_job_item_job (job_id, id)
);

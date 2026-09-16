ALTER TABLE document_agent_job
    ADD COLUMN dispatch_attempt INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME(3) NULL,
    ADD COLUMN dispatch_claim VARCHAR(64) NULL,
    ADD COLUMN artifact_id BIGINT NULL,
    ADD COLUMN revision_id BIGINT NULL,
    ADD COLUMN document_url VARCHAR(1024) NULL,
    ADD INDEX idx_document_agent_job_dispatch (status, next_attempt_at, time_created),
    ADD CONSTRAINT fk_document_agent_job_artifact FOREIGN KEY (artifact_id) REFERENCES project_document(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_document_agent_job_revision FOREIGN KEY (revision_id) REFERENCES project_document_revision(id) ON DELETE SET NULL;

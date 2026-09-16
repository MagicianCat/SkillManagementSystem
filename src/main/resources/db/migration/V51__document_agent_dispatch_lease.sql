ALTER TABLE document_agent_job
    ADD COLUMN dispatch_lease_until DATETIME(3) NULL,
    ADD INDEX idx_document_agent_job_lease (status, dispatch_lease_until);

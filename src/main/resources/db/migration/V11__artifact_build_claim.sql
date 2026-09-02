ALTER TABLE artifact_build_task ADD COLUMN retry_count int NOT NULL DEFAULT 0;
ALTER TABLE artifact_build_task ADD COLUMN next_retry_at datetime(3) NULL;
ALTER TABLE artifact_build_task ADD COLUMN lease_until datetime(3) NULL;
CREATE INDEX idx_build_claim ON artifact_build_task(status,next_retry_at,time_created);

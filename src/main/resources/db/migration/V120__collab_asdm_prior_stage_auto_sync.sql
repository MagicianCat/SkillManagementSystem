ALTER TABLE collab_asdm_sync_job
  ADD COLUMN trigger_kind VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
  ADD COLUMN source_candidate_id BIGINT NULL,
  ADD COLUMN source_stage_order INT NULL,
  ADD COLUMN summary_sent_status VARCHAR(32) NULL,
  ADD COLUMN summary_sent_at DATETIME(3) NULL;

CREATE INDEX idx_asdm_sync_job_summary ON collab_asdm_sync_job(trigger_kind,status,summary_sent_at);

ALTER TABLE skill_bundle ADD COLUMN retry_count int NOT NULL DEFAULT 0;
ALTER TABLE skill_bundle ADD COLUMN next_retry_at datetime(3) NULL;
ALTER TABLE skill_bundle ADD COLUMN error_code varchar(64) NULL;
ALTER TABLE skill_bundle ADD COLUMN error_message varchar(1024) NULL;

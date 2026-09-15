ALTER TABLE org_team ADD COLUMN last_seen_sync_run_id BIGINT NULL;
ALTER TABLE org_team_member ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE org_team_member ADD COLUMN last_seen_sync_run_id BIGINT NULL;
ALTER TABLE org_team_member ADD COLUMN last_synced_at DATETIME(3) NULL;
CREATE INDEX idx_org_team_status_seen ON org_team(status, last_seen_sync_run_id);
CREATE INDEX idx_org_team_member_status_seen ON org_team_member(team_id, status, last_seen_sync_run_id);

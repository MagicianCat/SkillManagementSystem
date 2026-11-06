ALTER TABLE ops_alert_instance
 ADD COLUMN recovery_count INT NOT NULL DEFAULT 0,
 ADD COLUMN last_evaluated_event_id BIGINT NULL,
 ADD COLUMN notification_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE ops_alert_recipient DROP PRIMARY KEY, ADD PRIMARY KEY(user_id,scope_code,channel);
UPDATE ops_alert_instance SET alert_no=CONCAT('ALT-',DATE_FORMAT(first_seen_at,'%Y%m%d'),'-',LPAD(id,6,'0')) WHERE alert_no IS NULL;
UPDATE ops_alert_instance a JOIN ops_alert_rule r ON r.rule_code='DSH_CREATE_CONSECUTIVE_FAILURE' SET a.rule_id=r.id WHERE a.rule_id IS NULL AND a.module='DSH_mcp';

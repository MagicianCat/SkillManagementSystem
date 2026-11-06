ALTER TABLE ops_event
 ADD COLUMN schema_version SMALLINT NOT NULL DEFAULT 1,
 ADD COLUMN ingested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD COLUMN actor_user_id BIGINT NULL,
 ADD COLUMN project_key VARCHAR(128) NULL,
 ADD COLUMN requirement_key VARCHAR(128) NULL,
 ADD COLUMN stage_id BIGINT NULL,
 ADD COLUMN aggregate_type VARCHAR(48) NULL,
 ADD COLUMN aggregate_id VARCHAR(128) NULL,
 ADD COLUMN is_effective_progress BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN dependency VARCHAR(80) NULL,
 ADD COLUMN operation VARCHAR(80) NULL,
 ADD COLUMN duration_ms BIGINT NULL,
 ADD COLUMN safe_attributes JSON NULL,
 ADD INDEX idx_ops_event_time_module(occurred_at,module,event_type),
 ADD INDEX idx_ops_event_run(run_key,occurred_at),
 ADD INDEX idx_ops_event_requirement(requirement_key,occurred_at),
 ADD INDEX idx_ops_event_progress(aggregate_type,aggregate_id,is_effective_progress,occurred_at);
CREATE TABLE ops_metric_bucket (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 bucket_start DATETIME(3) NOT NULL,
 bucket_minutes SMALLINT NOT NULL DEFAULT 1,
 module VARCHAR(48) NOT NULL,
 operation VARCHAR(80) NOT NULL,
 scope_code VARCHAR(96) NOT NULL DEFAULT 'GLOBAL',
 sample_count BIGINT NOT NULL DEFAULT 0,
 success_count BIGINT NOT NULL DEFAULT 0,
 failure_count BIGINT NOT NULL DEFAULT 0,
 degraded_count BIGINT NOT NULL DEFAULT 0,
 duration_sum_ms BIGINT NOT NULL DEFAULT 0,
 duration_histogram_json JSON NULL,
 calculated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_ops_metric_bucket(bucket_start,bucket_minutes,module,operation,scope_code),
 KEY idx_ops_metric_dimension(module,operation,scope_code,bucket_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE ops_telemetry_quality (
 generation_event_id BIGINT NOT NULL PRIMARY KEY,
 actor_user_id BIGINT NOT NULL,
 client_installation_id VARCHAR(128) NULL,
 protocol_version VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
 generation_started_at DATETIME(3) NOT NULL,
 capture_revision BIGINT NOT NULL DEFAULT 0,
 quality_grade VARCHAR(32) NOT NULL,
 missing_fields_json JSON NULL,
 expected_capabilities_json JSON NULL,
 grace_until DATETIME(3) NOT NULL,
 assessed_at DATETIME(3) NOT NULL,
 KEY idx_ops_quality_consecutive(actor_user_id,client_installation_id,generation_started_at),
 KEY idx_ops_quality_grade_time(quality_grade,assessed_at),
 CONSTRAINT fk_ops_quality_generation FOREIGN KEY(generation_event_id) REFERENCES ai_generation_event(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE ops_alert_rule (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 rule_code VARCHAR(96) NOT NULL,
 display_name VARCHAR(200) NOT NULL,
 module VARCHAR(48) NOT NULL,
 rule_type VARCHAR(32) NOT NULL,
 severity VARCHAR(8) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 evaluation_interval_sec INT NOT NULL DEFAULT 60,
 rule_config_json JSON NOT NULL,
 recovery_config_json JSON NOT NULL,
 default_config BOOLEAN NOT NULL DEFAULT TRUE,
 version_no INT NOT NULL DEFAULT 0,
 created_by BIGINT NULL,
 updated_by BIGINT NULL,
 time_created DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 time_updated DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_ops_alert_rule_code(rule_code),
 KEY idx_ops_alert_rule_enabled(enabled,module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE ops_alert_instance
 ADD COLUMN alert_no VARCHAR(48) NULL,
 ADD COLUMN rule_id BIGINT NULL,
 ADD COLUMN environment VARCHAR(32) NOT NULL DEFAULT 'default',
 ADD COLUMN scope_code VARCHAR(192) NOT NULL DEFAULT 'GLOBAL',
 ADD COLUMN summary VARCHAR(1000) NULL,
 ADD COLUMN error_code VARCHAR(128) NULL,
 ADD COLUMN occurrence_count BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN impact_json JSON NULL,
 ADD COLUMN rule_snapshot_json JSON NULL,
 ADD COLUMN trace_id VARCHAR(120) NULL,
 ADD COLUMN silence_until DATETIME(3) NULL,
 ADD COLUMN resolution_reason VARCHAR(256) NULL,
 ADD COLUMN version_no INT NOT NULL DEFAULT 0,
 ADD COLUMN last_evaluated_at DATETIME(3) NULL,
 ADD COLUMN last_notify_at DATETIME(3) NULL,
 ADD COLUMN time_updated DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD UNIQUE KEY uk_ops_alert_no(alert_no),
 ADD INDEX idx_ops_alert_scope(module,scope_code,first_seen_at);
ALTER TABLE ops_alert_recipient
 ADD COLUMN scope_code VARCHAR(96) NOT NULL DEFAULT 'ALL',
 ADD COLUMN min_severity VARCHAR(8) NOT NULL DEFAULT 'P1',
 ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'FEISHU',
 ADD COLUMN version_no INT NOT NULL DEFAULT 0;
ALTER TABLE ops_alert_delivery
 ADD COLUMN idempotency_key VARCHAR(192) NULL,
 ADD COLUMN claimed_by VARCHAR(96) NULL,
 ADD COLUMN feishu_message_id VARCHAR(128) NULL,
 ADD COLUMN last_error_code VARCHAR(128) NULL,
 ADD COLUMN time_updated DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD UNIQUE KEY uk_ops_delivery_idempotency(idempotency_key);
CREATE TABLE ops_alert_action_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 alert_id BIGINT NULL,
 actor_user_id BIGINT NOT NULL,
 action_type VARCHAR(48) NOT NULL,
 request_id VARCHAR(128) NULL,
 before_json JSON NULL,
 after_json JSON NULL,
 occurred_at DATETIME(3) NOT NULL,
 KEY idx_ops_alert_action_time(occurred_at),
 KEY idx_ops_alert_action_alert(alert_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE ops_probe_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 component_key VARCHAR(96) NOT NULL,
 checked_at DATETIME(3) NOT NULL,
 status VARCHAR(16) NOT NULL,
 latency_ms BIGINT NULL,
 error_code VARCHAR(128) NULL,
 safe_summary VARCHAR(500) NULL,
 KEY idx_ops_probe_component_time(component_key,checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE ops_alert_settings (
 setting_key VARCHAR(64) NOT NULL PRIMARY KEY,
 setting_json JSON NOT NULL,
 version_no INT NOT NULL DEFAULT 0,
 updated_by BIGINT NULL,
 time_updated DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO ops_alert_settings(setting_key,setting_json) VALUES('DEFAULT_ALERT_NOTIFICATION','{"enabled":true,"initialMinutes":5,"factor":2,"maxMinutes":240,"maxMessagesPerRecipientHour":20}');
INSERT INTO iam_permission(permission_key,permission_name,description,time_created,time_updated) VALUES
 ('admin:alert:operate','告警操作','确认与静默告警',now(3),now(3)),
 ('admin:alert:configure','告警配置','配置告警规则和飞书收件人',now(3),now(3))
 ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),description=VALUES(description);
INSERT IGNORE INTO iam_role_permission(role_id,permission_id,created_by,time_created,time_updated)
 SELECT r.id,p.id,NULL,now(3),now(3) FROM iam_role r JOIN iam_permission p ON p.permission_key IN('admin:alert:operate','admin:alert:configure') WHERE r.role_key='ADMIN';

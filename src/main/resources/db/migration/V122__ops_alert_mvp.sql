CREATE TABLE ops_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key VARCHAR(180) NOT NULL,
 event_type VARCHAR(100) NOT NULL,
 module VARCHAR(40) NOT NULL,
 outcome VARCHAR(20) NOT NULL,
 fingerprint VARCHAR(180) NOT NULL,
 run_key VARCHAR(120) NULL,
 request_id VARCHAR(120) NULL,
 error_code VARCHAR(80) NULL,
 phase VARCHAR(60) NULL,
 cause_type VARCHAR(100) NULL,
 occurred_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_ops_event_key(event_key),
 KEY idx_ops_event_rule(fingerprint,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_alert_instance (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 fingerprint VARCHAR(180) NOT NULL,
 active_fingerprint VARCHAR(180) NULL,
 rule_code VARCHAR(80) NOT NULL,
 module VARCHAR(40) NOT NULL,
 severity VARCHAR(8) NOT NULL,
 status VARCHAR(20) NOT NULL,
 title VARCHAR(180) NOT NULL,
 latest_event_id BIGINT NOT NULL,
 first_seen_at DATETIME(3) NOT NULL,
 last_seen_at DATETIME(3) NOT NULL,
 acknowledged_at DATETIME(3) NULL,
 acknowledged_by BIGINT NULL,
 resolved_at DATETIME(3) NULL,
 next_reminder_at DATETIME(3) NULL,
 reminder_count INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_ops_active_fingerprint(active_fingerprint),
 KEY idx_ops_alert_status(status,next_reminder_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_alert_recipient (
 user_id BIGINT NOT NULL PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 configured_by BIGINT NOT NULL,
 time_updated DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ops_alert_delivery (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 alert_id BIGINT NOT NULL,
 recipient_user_id BIGINT NOT NULL,
 kind VARCHAR(20) NOT NULL,
 batch_no INT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NOT NULL,
 lease_until DATETIME(3) NULL,
 sent_at DATETIME(3) NULL,
 last_error VARCHAR(250) NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_ops_delivery(alert_id,recipient_user_id,kind,batch_no),
 KEY idx_ops_delivery_due(status,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

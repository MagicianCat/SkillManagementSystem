CREATE TABLE external_role_card_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  source_system VARCHAR(128) NOT NULL,
  external_role_id VARCHAR(255) NOT NULL,
  role_name VARCHAR(512) NOT NULL,
  role_status VARCHAR(16) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  change_summary_json JSON NOT NULL,
  resource_snapshot_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  last_error VARCHAR(2048) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at DATETIME(3) NULL,
  UNIQUE KEY uk_external_role_card_request(role_id,request_id),
  KEY ix_external_role_card_pending(status,next_attempt_at),
  CONSTRAINT fk_external_role_card_role FOREIGN KEY(role_id) REFERENCES external_role(id)
);

CREATE TABLE external_role_card_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  recipient_user_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  feishu_message_id VARCHAR(128) NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  last_error VARCHAR(2048) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at DATETIME(3) NULL,
  UNIQUE KEY uk_external_role_card_delivery(event_id,recipient_user_id),
  KEY ix_external_role_card_delivery_pending(status,next_attempt_at),
  CONSTRAINT fk_external_role_card_delivery_event FOREIGN KEY(event_id) REFERENCES external_role_card_event(id),
  CONSTRAINT fk_external_role_card_delivery_user FOREIGN KEY(recipient_user_id) REFERENCES iam_user(id)
);

CREATE TABLE external_role_card_action (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  action_key VARCHAR(128) NOT NULL,
  operator_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
  job_id BIGINT NULL,
  request_payload_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  UNIQUE KEY uk_external_role_card_action(event_id,action_key),
  CONSTRAINT fk_external_role_card_action_event FOREIGN KEY(event_id) REFERENCES external_role_card_event(id),
  CONSTRAINT fk_external_role_card_action_user FOREIGN KEY(operator_id) REFERENCES iam_user(id),
  CONSTRAINT fk_external_role_card_action_job FOREIGN KEY(job_id) REFERENCES external_import_job(id)
);

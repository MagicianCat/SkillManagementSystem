ALTER TABLE collab_requirement_progress_doc
  ADD COLUMN chat_permission_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN chat_permission_last_attempt_at DATETIME(3) NULL;

CREATE TABLE collab_requirement_progress_refresh (
  requirement_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (requirement_id,idempotency_key),
  CONSTRAINT fk_req_progress_refresh_requirement FOREIGN KEY (requirement_id)
    REFERENCES collab_project(id) ON DELETE RESTRICT,
  INDEX idx_req_progress_refresh_time (requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

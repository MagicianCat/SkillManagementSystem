CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NULL,
    request_id VARCHAR(64) NOT NULL,
    result VARCHAR(32) NOT NULL,
    reason VARCHAR(1024) NULL,
    before_json JSON NULL,
    after_json JSON NULL,
    metadata_json JSON NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES iam_user (id) ON DELETE RESTRICT,
    INDEX idx_audit_target (target_type, target_id, time_created),
    INDEX idx_audit_actor (actor_user_id, time_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

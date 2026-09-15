ALTER TABLE agent_session
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER status,
    ADD INDEX idx_agent_session_retention (status, deleted_at);

CREATE TABLE skill_bundle_failure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    bundle_id BIGINT NOT NULL,
    skill_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NULL,
    error_code VARCHAR(64) NOT NULL,
    error_message VARCHAR(1024) NOT NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_bundle_failure (bundle_id, skill_key),
    CONSTRAINT fk_bundle_failure_bundle FOREIGN KEY (bundle_id) REFERENCES skill_bundle(id) ON DELETE CASCADE,
    INDEX idx_bundle_failure_bundle (bundle_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    session_key CHAR(36) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    profile_key VARCHAR(64) NOT NULL,
    title VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    platform VARCHAR(64) NULL,
    os_type VARCHAR(32) NULL,
    last_message_at DATETIME(3) NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_session_key UNIQUE (session_key),
    CONSTRAINT fk_agent_session_owner FOREIGN KEY (owner_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_agent_session_owner (owner_user_id, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    run_key CHAR(36) NOT NULL,
    session_id BIGINT NOT NULL,
    run_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    runtime_run_id VARCHAR(128) NULL,
    runtime_version VARCHAR(64) NOT NULL,
    model_key VARCHAR(128) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_run_key UNIQUE (run_key),
    CONSTRAINT uk_agent_session_run UNIQUE (session_id, run_no),
    CONSTRAINT fk_agent_run_session FOREIGN KEY (session_id) REFERENCES agent_session(id) ON DELETE RESTRICT,
    INDEX idx_agent_run_status (status, time_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    session_id BIGINT NOT NULL,
    run_id BIGINT NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_message_sequence UNIQUE (session_id, sequence_no),
    CONSTRAINT fk_agent_message_session FOREIGN KEY (session_id) REFERENCES agent_session(id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_message_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE RESTRICT,
    INDEX idx_agent_message_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE agent_recommendation
    ADD COLUMN run_id BIGINT NULL AFTER run_ref,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'VALID' AFTER summary,
    ADD COLUMN validated_at DATETIME(3) NULL AFTER status,
    ADD CONSTRAINT fk_agent_recommendation_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE RESTRICT,
    ADD INDEX idx_agent_recommendation_run (run_id);

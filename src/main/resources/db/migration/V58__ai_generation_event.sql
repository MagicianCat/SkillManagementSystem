CREATE TABLE ai_generation_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,

    client_installation_id VARCHAR(64) NULL,
    client_session_id VARCHAR(256) NOT NULL,
    hook_generation_id VARCHAR(256) NOT NULL,

    project_key VARCHAR(128) NULL,
    project_name VARCHAR(256) NULL,
    project_source VARCHAR(32) NULL,

    primary_stage VARCHAR(32) NULL,

    started_at DATETIME(3) NOT NULL,
    ended_at DATETIME(3) NULL,
    duration_ms BIGINT NULL,

    status VARCHAR(32) NOT NULL,

    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    total_tokens BIGINT NULL,

    cache_read_tokens BIGINT NULL,
    cache_write_tokens BIGINT NULL,
    cache_miss_tokens BIGINT NULL,

    thinking_tokens BIGINT NULL,

    model_call_count INT NULL,
    last_tokens BIGINT NULL,

    token_source VARCHAR(32) NULL,
    token_quality VARCHAR(32) NULL,

    lines_added BIGINT NOT NULL DEFAULT 0,
    lines_deleted BIGINT NOT NULL DEFAULT 0,

    files_created INT NOT NULL DEFAULT 0,
    files_modified INT NOT NULL DEFAULT 0,

    tool_call_count INT NOT NULL DEFAULT 0,
    tool_failure_count INT NOT NULL DEFAULT 0,

    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_generation_user_generation UNIQUE (user_id, hook_generation_id),
    CONSTRAINT fk_ai_generation_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_generation_user_time (user_id, started_at),
    INDEX idx_generation_session_time (client_session_id, started_at),
    INDEX idx_generation_project_time (project_key, started_at),
    INDEX idx_generation_stage_time (primary_stage, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE skill_usage_event ADD COLUMN conversation_chunk_object_key VARCHAR(512) NULL;
ALTER TABLE skill_usage_event ADD COLUMN conversation_attempts INT NOT NULL DEFAULT 0;

CREATE TABLE skill_usage_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    user_id BIGINT NOT NULL,
    client_session_id VARCHAR(256) NOT NULL,
    current_object_key VARCHAR(512) NULL,
    conversation_version BIGINT NOT NULL DEFAULT 0,
    message_count INT NOT NULL DEFAULT 0,
    last_sampled_at DATETIME(3) NULL,
    offline_persisted_version BIGINT NULL,
    offline_persisted_at DATETIME(3) NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_skill_usage_conversation UNIQUE (user_id, client_session_id),
    CONSTRAINT fk_skill_usage_conversation_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

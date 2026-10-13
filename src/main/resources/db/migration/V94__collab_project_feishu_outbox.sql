CREATE TABLE collab_feishu_binding (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    chat_id VARCHAR(128) NULL,
    wiki_space_id VARCHAR(128) NULL,
    wiki_root_node_token VARCHAR(128) NULL,
    wiki_parent_node_token VARCHAR(128) NULL,
    publish_actor_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_verified_at DATETIME(3) NULL,
    last_error VARCHAR(2000) NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    CONSTRAINT uk_collab_feishu_binding_project UNIQUE (project_id),
    CONSTRAINT fk_collab_feishu_binding_project FOREIGN KEY (project_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_feishu_binding_actor FOREIGN KEY (publish_actor_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_feishu_publication (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    artifact_version_id BIGINT NOT NULL,
    node_token VARCHAR(128) NULL,
    document_token VARCHAR(128) NULL,
    url VARCHAR(2000) NULL,
    publish_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(2000) NULL,
    published_at DATETIME(3) NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    CONSTRAINT uk_collab_feishu_publication_version UNIQUE (artifact_version_id),
    CONSTRAINT fk_collab_feishu_publication_version FOREIGN KEY (artifact_version_id) REFERENCES collab_artifact_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    unique_dedupe_key VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    claimed_at DATETIME(3) NULL,
    last_error VARCHAR(2000) NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    CONSTRAINT uk_collab_outbox_dedupe UNIQUE (unique_dedupe_key),
    CONSTRAINT fk_collab_outbox_project FOREIGN KEY (project_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
    INDEX idx_collab_outbox_ready (status,next_attempt_at),
    INDEX idx_collab_outbox_project (project_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

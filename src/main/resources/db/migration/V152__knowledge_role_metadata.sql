CREATE TABLE knowledge_role_metadata_sync (
    document_id BIGINT NOT NULL PRIMARY KEY,
    desired_generation BIGINT NOT NULL DEFAULT 1,
    applied_generation BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until DATETIME(3) NULL,
    lease_token VARCHAR(64) NULL,
    last_error_code VARCHAR(128) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX ix_role_sync_due(status, next_attempt_at)
);

CREATE TABLE knowledge_role_metadata_scan (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    cursor_id BIGINT NOT NULL DEFAULT 0,
    high_watermark BIGINT NOT NULL,
    scanned BIGINT NOT NULL DEFAULT 0,
    verified BIGINT NOT NULL DEFAULT 0,
    queued BIGINT NOT NULL DEFAULT 0,
    waiting_index BIGINT NOT NULL DEFAULT 0,
    failed BIGINT NOT NULL DEFAULT 0,
    operator_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    active_slot INT GENERATED ALWAYS AS (CASE WHEN status='RUNNING' THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_role_metadata_scan_active(active_slot)
);

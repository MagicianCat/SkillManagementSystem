CREATE TABLE skill_review (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_version_id BIGINT NOT NULL, review_no INT NOT NULL, status VARCHAR(32) NOT NULL,
    submitter_id BIGINT NOT NULL, reviewer_id BIGINT NULL, submit_comment TEXT NULL, review_comment TEXT NULL,
    submitted_at DATETIME(3) NOT NULL, reviewed_at DATETIME(3) NULL,
    pending_version_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN skill_version_id ELSE NULL END) STORED,
    PRIMARY KEY(id), CONSTRAINT uk_review_no UNIQUE(skill_version_id,review_no), CONSTRAINT uk_review_pending UNIQUE(pending_version_id),
    CONSTRAINT fk_review_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_submitter FOREIGN KEY(submitter_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_reviewer FOREIGN KEY(reviewer_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_review_status(status,submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_version_id BIGINT NOT NULL, from_status VARCHAR(32) NULL, to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(1024) NULL, operator_id BIGINT NOT NULL, PRIMARY KEY(id),
    CONSTRAINT fk_history_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_history_operator FOREIGN KEY(operator_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE artifact_build_task (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_version_id BIGINT NOT NULL, task_type VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL, started_at DATETIME(3) NULL, finished_at DATETIME(3) NULL,
    error_code VARCHAR(64) NULL, error_message TEXT NULL, created_by BIGINT NOT NULL, version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY(id), CONSTRAINT uk_build_idempotency UNIQUE(idempotency_key),
    CONSTRAINT fk_build_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_creator FOREIGN KEY(created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_build_status(status,time_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

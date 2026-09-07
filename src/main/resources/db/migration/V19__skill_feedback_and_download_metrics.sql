CREATE TABLE skill_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    skill_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    comment TEXT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_skill_feedback_user UNIQUE (skill_id, user_id),
    CONSTRAINT fk_feedback_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    INDEX idx_feedback_skill_created (skill_id, time_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE download_log ADD COLUMN skill_id BIGINT NULL AFTER user_id;
ALTER TABLE download_log ADD CONSTRAINT fk_download_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT;
ALTER TABLE download_log ADD INDEX idx_download_skill (skill_id, time_created);

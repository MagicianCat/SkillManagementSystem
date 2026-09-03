CREATE TABLE user_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    recipient_id BIGINT NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    content VARCHAR(2048) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NOT NULL,
    skill_id BIGINT NULL,
    skill_version_id BIGINT NULL,
    read_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_version FOREIGN KEY (skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    INDEX idx_notification_inbox (recipient_id, read_at, time_created),
    INDEX idx_notification_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE skill_rating (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    skill_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating INT NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_skill_rating_user UNIQUE (skill_id, user_id),
    CONSTRAINT fk_rating_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rating_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_skill_rating CHECK (rating BETWEEN 1 AND 5),
    INDEX idx_skill_rating_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_comment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    skill_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    comment TEXT NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_comment_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_skill_comment_created (skill_id, time_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO skill_rating (time_created, time_updated, skill_id, user_id, rating, version_no)
SELECT time_created, time_updated, skill_id, user_id, rating, version_no FROM skill_feedback;

INSERT INTO skill_comment (time_created, time_updated, skill_id, user_id, comment, version_no)
SELECT time_created, time_updated, skill_id, user_id, TRIM(comment), version_no
FROM skill_feedback
WHERE comment IS NOT NULL AND CHAR_LENGTH(TRIM(comment)) > 0;

DROP TABLE skill_feedback;

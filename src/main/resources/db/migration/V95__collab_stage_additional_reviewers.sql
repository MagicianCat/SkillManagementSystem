CREATE TABLE IF NOT EXISTS collab_stage_additional_reviewer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    stage_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_stage_additional_reviewer UNIQUE (stage_id, reviewer_user_id),
    CONSTRAINT fk_collab_stage_additional_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_stage_additional_user FOREIGN KEY (reviewer_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_stage_additional_reviewer (reviewer_user_id, stage_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE collab_approval DROP FOREIGN KEY fk_collab_approval_submission;
ALTER TABLE collab_approval DROP INDEX uk_collab_approval_submission;
ALTER TABLE collab_approval ADD CONSTRAINT uk_collab_approval_submission_reviewer UNIQUE (submission_id, reviewer_user_id);
ALTER TABLE collab_approval ADD CONSTRAINT fk_collab_approval_submission FOREIGN KEY (submission_id) REFERENCES collab_submission(id) ON DELETE RESTRICT;

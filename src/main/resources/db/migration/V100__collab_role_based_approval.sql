CREATE TABLE collab_requirement_stage_reviewer_role_actor (
    stage_id BIGINT NOT NULL,
    requirement_role_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'INHERITED',
    time_created DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (stage_id,requirement_role_id,user_id),
    CONSTRAINT fk_collab_req_reviewer_role_actor_stage_role FOREIGN KEY (stage_id,requirement_role_id)
        REFERENCES collab_requirement_stage_reviewer_role(stage_id,requirement_role_id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_req_reviewer_role_actor_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_req_reviewer_role_actor_user (user_id,stage_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- V99 retained the per-stage union of reviewer actors, but not their per-role membership.
-- Reconstruct the most specific mapping available by intersecting that frozen union with
-- the current group role membership. Role snapshots with no matching member inherit the
-- frozen union as a compatibility fallback so migrated requirements remain actionable.
INSERT INTO collab_requirement_stage_reviewer_role_actor(stage_id,requirement_role_id,user_id,source)
SELECT rr.stage_id,rr.requirement_role_id,a.user_id,'INHERITED'
FROM collab_requirement_stage_reviewer_role rr
JOIN collab_stage s ON s.id=rr.stage_id
JOIN collab_project p ON p.id=s.project_id
JOIN collab_requirement_role req_role ON req_role.id=rr.requirement_role_id
JOIN collab_group_role group_role ON group_role.group_id=p.group_id
    AND group_role.role_key=req_role.source_group_role_key
JOIN collab_group_role_member member ON member.role_id=group_role.id
JOIN collab_requirement_stage_actor a ON a.stage_id=rr.stage_id
    AND a.duty='REVIEWER' AND a.user_id=member.user_id
JOIN iam_user u ON u.id=a.user_id AND u.status='ACTIVE'
WHERE p.group_id IS NOT NULL;

INSERT INTO collab_requirement_stage_reviewer_role_actor(stage_id,requirement_role_id,user_id,source)
SELECT rr.stage_id,rr.requirement_role_id,a.user_id,'INHERITED'
FROM collab_requirement_stage_reviewer_role rr
JOIN collab_stage s ON s.id=rr.stage_id
JOIN collab_project p ON p.id=s.project_id
JOIN collab_requirement_stage_actor a ON a.stage_id=rr.stage_id AND a.duty='REVIEWER'
JOIN iam_user u ON u.id=a.user_id AND u.status='ACTIVE'
WHERE p.group_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM collab_requirement_stage_reviewer_role_actor mapped
      WHERE mapped.stage_id=rr.stage_id AND mapped.requirement_role_id=rr.requirement_role_id
  );

CREATE TABLE collab_submission_reviewer_role (
    submission_id BIGINT NOT NULL,
    requirement_role_id BIGINT NOT NULL,
    role_key_snapshot VARCHAR(64) NOT NULL,
    role_name_snapshot VARCHAR(100) NOT NULL,
    PRIMARY KEY (submission_id,requirement_role_id),
    CONSTRAINT fk_collab_submission_reviewer_role_submission FOREIGN KEY (submission_id)
        REFERENCES collab_submission(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_submission_reviewer_role_requirement_role FOREIGN KEY (requirement_role_id)
        REFERENCES collab_requirement_role(id) ON DELETE RESTRICT,
    INDEX idx_collab_submission_reviewer_role_requirement_role (requirement_role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_submission_reviewer_assignment (
    submission_id BIGINT NOT NULL,
    requirement_role_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    approval_id BIGINT NOT NULL,
    PRIMARY KEY (submission_id,requirement_role_id,reviewer_user_id),
    CONSTRAINT fk_collab_submission_reviewer_assignment_role FOREIGN KEY (submission_id,requirement_role_id)
        REFERENCES collab_submission_reviewer_role(submission_id,requirement_role_id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_submission_reviewer_assignment_approval FOREIGN KEY (approval_id)
        REFERENCES collab_approval(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_submission_reviewer_assignment_user FOREIGN KEY (reviewer_user_id)
        REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_submission_reviewer_assignment_approval (approval_id),
    INDEX idx_collab_submission_reviewer_assignment_user (submission_id,reviewer_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

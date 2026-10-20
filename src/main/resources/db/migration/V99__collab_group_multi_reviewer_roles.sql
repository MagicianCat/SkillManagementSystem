CREATE TABLE collab_group_stage_reviewer_role (
    group_id BIGINT NOT NULL,
    stage_key VARCHAR(48) NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (group_id,stage_key,role_id),
    CONSTRAINT fk_collab_group_stage_reviewer_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_stage_reviewer_role_ref FOREIGN KEY (role_id) REFERENCES collab_group_role(id) ON DELETE RESTRICT,
    INDEX idx_collab_group_stage_reviewer_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO collab_group_stage_reviewer_role(group_id,stage_key,role_id)
SELECT t.group_id,t.stage_key,t.reviewer_role_id
FROM collab_group_stage_template t
WHERE t.reviewer_role_id IS NOT NULL;

CREATE TABLE collab_requirement_stage_reviewer_role (
    stage_id BIGINT NOT NULL,
    requirement_role_id BIGINT NOT NULL,
    PRIMARY KEY (stage_id,requirement_role_id),
    CONSTRAINT fk_collab_req_stage_reviewer_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_req_stage_reviewer_role FOREIGN KEY (requirement_role_id) REFERENCES collab_requirement_role(id) ON DELETE RESTRICT,
    INDEX idx_collab_req_stage_reviewer_role (requirement_role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO collab_requirement_stage_reviewer_role(stage_id,requirement_role_id)
SELECT sr.stage_id,sr.requirement_role_id
FROM collab_requirement_stage_role sr
WHERE sr.duty='REVIEWER';

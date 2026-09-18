-- Project workflow control plane. Later delivery phases are represented as FUTURE
-- placeholders and deliberately excluded from progress calculations.
CREATE TABLE project_member_functional_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    assigned_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_functional_role UNIQUE (project_id, user_id, role_key),
    CONSTRAINT fk_project_role_project FOREIGN KEY (project_id) REFERENCES virtual_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_role_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_role_assigner FOREIGN KEY (assigned_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_project_role_lookup (project_id, role_key, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_stage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    stage_key VARCHAR(64) NOT NULL,
    stage_order INT NOT NULL,
    availability VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    cycle_no INT NOT NULL DEFAULT 0,
    started_by BIGINT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_stage UNIQUE (project_id, stage_key),
    CONSTRAINT fk_project_stage_project FOREIGN KEY (project_id) REFERENCES virtual_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_stage_starter FOREIGN KEY (started_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_project_stage_order (project_id, stage_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_stage_dependency (
    stage_id BIGINT NOT NULL,
    depends_on_stage_id BIGINT NOT NULL,
    PRIMARY KEY (stage_id, depends_on_stage_id),
    CONSTRAINT fk_stage_dependency_stage FOREIGN KEY (stage_id) REFERENCES project_stage(id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_dependency_parent FOREIGN KEY (depends_on_stage_id) REFERENCES project_stage(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_stage_skill (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    stage_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    locked_version_id BIGINT NULL,
    locked_version VARCHAR(32) NULL,
    locked_sha256 CHAR(64) NULL,
    locked_content MEDIUMTEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_stage_skill UNIQUE (stage_id, skill_id),
    CONSTRAINT fk_project_stage_skill_stage FOREIGN KEY (stage_id) REFERENCES project_stage(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_stage_skill_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_stage_skill_version FOREIGN KEY (locked_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_stage_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    stage_id BIGINT NOT NULL,
    cycle_no INT NOT NULL,
    document_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    submitted_by BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    decided_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_stage_submission UNIQUE (stage_id, cycle_no),
    CONSTRAINT fk_stage_submission_stage FOREIGN KEY (stage_id) REFERENCES project_stage(id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_submission_document FOREIGN KEY (document_id) REFERENCES project_document(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage_submission_revision FOREIGN KEY (revision_id) REFERENCES project_document_revision(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage_submission_submitter FOREIGN KEY (submitted_by) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_stage_reviewer (
    submission_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    PRIMARY KEY (submission_id, reviewer_user_id),
    CONSTRAINT fk_stage_reviewer_submission FOREIGN KEY (submission_id) REFERENCES project_stage_submission(id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_reviewer_user FOREIGN KEY (reviewer_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_stage_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    submission_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    comment VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stage_review UNIQUE (submission_id, reviewer_user_id),
    CONSTRAINT fk_stage_review_submission FOREIGN KEY (submission_id) REFERENCES project_stage_submission(id) ON DELETE CASCADE,
    CONSTRAINT fk_stage_review_user FOREIGN KEY (reviewer_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO project_stage(time_created,time_updated,project_id,stage_key,stage_order,availability,enabled,status,cycle_no,version_no)
SELECT NOW(3),NOW(3),p.id,x.stage_key,x.stage_order,x.availability,x.enabled,'NOT_STARTED',0,0
FROM virtual_project p
JOIN (
    SELECT 'REQUIREMENT' stage_key,1 stage_order,'AVAILABLE' availability,TRUE enabled UNION ALL
    SELECT 'PRD',2,'AVAILABLE',TRUE UNION ALL SELECT 'ARCHITECTURE',3,'AVAILABLE',TRUE UNION ALL
    SELECT 'UI_DESIGN',4,'AVAILABLE',TRUE UNION ALL SELECT 'CODING',5,'FUTURE',FALSE UNION ALL
    SELECT 'SECURITY',6,'FUTURE',FALSE UNION ALL SELECT 'TESTING',7,'FUTURE',FALSE UNION ALL
    SELECT 'RELEASE',8,'FUTURE',FALSE
) x;

INSERT INTO project_stage_dependency(stage_id,depends_on_stage_id)
SELECT child.id,parent.id FROM project_stage child JOIN project_stage parent ON parent.project_id=child.project_id
WHERE (child.stage_key='PRD' AND parent.stage_key='REQUIREMENT')
   OR (child.stage_key IN ('ARCHITECTURE','UI_DESIGN') AND parent.stage_key='PRD');

ALTER TABLE document_agent_session ADD COLUMN stage_id BIGINT NULL AFTER project_id;
ALTER TABLE document_agent_session ADD CONSTRAINT fk_document_agent_session_stage FOREIGN KEY (stage_id) REFERENCES project_stage(id) ON DELETE SET NULL;
ALTER TABLE document_agent_session ADD INDEX idx_document_agent_session_stage (stage_id, status);
ALTER TABLE document_agent_job ADD COLUMN requested_by BIGINT NULL AFTER session_id;
ALTER TABLE document_agent_job ADD CONSTRAINT fk_document_agent_job_requester FOREIGN KEY (requested_by) REFERENCES iam_user(id) ON DELETE RESTRICT;

INSERT INTO iam_permission(permission_key, permission_name, description, time_created, time_updated)
SELECT 'project:create', '创建项目组', '允许项目管理人发起跨团队项目', NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission WHERE permission_key='project:create');

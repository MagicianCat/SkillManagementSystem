CREATE TABLE org_team (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_department_id VARCHAR(128) NOT NULL,
    parent_id BIGINT NULL,
    team_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_org_team_external UNIQUE (provider, external_department_id),
    CONSTRAINT fk_org_team_parent FOREIGN KEY (parent_id) REFERENCES org_team(id) ON DELETE RESTRICT,
    INDEX idx_org_team_parent (parent_id, team_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE org_team_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    membership_type VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    PRIMARY KEY (id),
    CONSTRAINT uk_org_team_member UNIQUE (team_id, user_id),
    CONSTRAINT fk_org_member_team FOREIGN KEY (team_id) REFERENCES org_team(id) ON DELETE RESTRICT,
    CONSTRAINT fk_org_member_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_scoped_role_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    user_id BIGINT NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    team_id BIGINT NULL,
    granted_by BIGINT NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_scoped_role UNIQUE (user_id, role_key, scope_type, team_id),
    CONSTRAINT fk_scoped_role_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_scoped_role_team FOREIGN KEY (team_id) REFERENCES org_team(id) ON DELETE RESTRICT,
    CONSTRAINT fk_scoped_role_grantor FOREIGN KEY (granted_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_scoped_role_team (team_id, role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE skill ADD COLUMN scope_type VARCHAR(32) NOT NULL DEFAULT 'PLATFORM' AFTER status;
ALTER TABLE skill ADD COLUMN team_id BIGINT NULL AFTER scope_type;
ALTER TABLE skill ADD CONSTRAINT fk_skill_team FOREIGN KEY (team_id) REFERENCES org_team(id) ON DELETE RESTRICT;
ALTER TABLE skill ADD INDEX idx_skill_scope (scope_type, team_id, status);

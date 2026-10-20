CREATE TABLE collab_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_key CHAR(36) NOT NULL,
    home_team_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NULL,
    owner_user_id BIGINT NOT NULL,
    gitlab_root_url VARCHAR(2048) NULL,
    gitlab_root_normalized VARCHAR(2048) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    template_version INT NOT NULL DEFAULT 1,
    version_no INT NOT NULL DEFAULT 0,
    legacy_import BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(3) NULL,
    created_by BIGINT NOT NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_group_key UNIQUE (group_key),
    CONSTRAINT fk_collab_group_team FOREIGN KEY (home_team_id) REFERENCES org_team(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_owner FOREIGN KEY (owner_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_creator FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_group_owner (owner_user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_group_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    member_type VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    updated_by BIGINT NOT NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_group_member UNIQUE (group_id,user_id),
    CONSTRAINT fk_collab_group_member_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_member_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_member_updater FOREIGN KEY (updated_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_group_member_user (user_id,status,group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_group_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version_no INT NOT NULL DEFAULT 0,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_group_role_key UNIQUE (group_id,role_key),
    CONSTRAINT fk_collab_group_role_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
    INDEX idx_collab_group_role_name (group_id,role_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_group_role_member (
    role_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (role_id,user_id),
    CONSTRAINT fk_collab_group_role_member_role FOREIGN KEY (role_id) REFERENCES collab_group_role(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_role_member_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_group_stage_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    stage_key VARCHAR(48) NOT NULL,
    owner_role_id BIGINT NULL,
    reviewer_role_id BIGINT NULL,
    default_included BOOLEAN NOT NULL DEFAULT TRUE,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_group_stage_template UNIQUE (group_id,stage_key),
    CONSTRAINT fk_collab_group_stage_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_stage_owner_role FOREIGN KEY (owner_role_id) REFERENCES collab_group_role(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_stage_reviewer_role FOREIGN KEY (reviewer_role_id) REFERENCES collab_group_role(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_group_template_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    actor_id BIGINT NOT NULL,
    changed_json JSON NOT NULL,
    time_created DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_collab_group_audit_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_audit_actor FOREIGN KEY (actor_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_group_audit_version (group_id,version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_group_feishu_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    chat_id VARCHAR(128) NULL,
    wiki_space_id VARCHAR(128) NULL,
    wiki_root_node_token VARCHAR(128) NULL,
    wiki_root_url VARCHAR(2000) NULL,
    wiki_group_node_token VARCHAR(128) NULL,
    publish_actor_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_verified_at DATETIME(3) NULL,
    last_error VARCHAR(2000) NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_group_feishu_binding UNIQUE (group_id),
    CONSTRAINT fk_collab_group_feishu_binding_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_group_feishu_binding_actor FOREIGN KEY (publish_actor_user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_group_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    unique_dedupe_key VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    claimed_at DATETIME(3) NULL,
    last_error VARCHAR(2000) NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_group_outbox_dedupe UNIQUE (unique_dedupe_key),
    CONSTRAINT fk_collab_group_outbox_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
    INDEX idx_collab_group_outbox_ready (status,next_attempt_at),
    INDEX idx_collab_group_outbox_group (group_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @legacy_group_key = UUID();
INSERT INTO collab_group (group_key,home_team_id,name,description,owner_user_id,gitlab_root_url,gitlab_root_normalized,status,template_version,version_no,legacy_import,published_at,created_by,time_created,time_updated)
SELECT @legacy_group_key,seed.home_team_id,'默认项目组（存量测试数据）','用于承载升级前已有的测试需求',seed.owner_user_id,NULL,NULL,'PUBLISHED',1,0,TRUE,NOW(3),seed.owner_user_id,NOW(3),NOW(3)
FROM collab_project seed
JOIN iam_user owner_user ON owner_user.id=seed.owner_user_id AND owner_user.status='ACTIVE'
JOIN org_team home_team ON home_team.id=seed.home_team_id AND home_team.status='ACTIVE'
WHERE seed.id=(SELECT MIN(id) FROM collab_project);
SET @legacy_group_id = (SELECT id FROM collab_group WHERE group_key=@legacy_group_key LIMIT 1);
INSERT INTO collab_group_feishu_binding (group_id,status,time_created,time_updated)
SELECT @legacy_group_id,'LEGACY_PENDING',NOW(3),NOW(3) WHERE @legacy_group_id IS NOT NULL;

INSERT INTO collab_group_member (group_id,user_id,member_type,status,updated_by,time_created,time_updated)
SELECT @legacy_group_id,users.user_id,IF(users.user_id=lg.owner_user_id,'OWNER','MEMBER'),'ACTIVE',lg.owner_user_id,NOW(3),NOW(3)
FROM (
    SELECT user_id FROM collab_project_member WHERE status='ACTIVE'
    UNION
    SELECT owner_user_id AS user_id FROM collab_project
) users
JOIN collab_group lg ON lg.id=@legacy_group_id
WHERE users.user_id IS NOT NULL
ON DUPLICATE KEY UPDATE status='ACTIVE',member_type=VALUES(member_type),time_updated=NOW(3);

ALTER TABLE collab_project ADD COLUMN group_id BIGINT NULL;
ALTER TABLE collab_project ADD COLUMN inherited_template_version INT NULL;
ALTER TABLE collab_project ADD COLUMN gitlab_root_snapshot VARCHAR(2048) NULL;
ALTER TABLE collab_project ADD INDEX idx_collab_requirement_group (group_id,status,time_updated);
ALTER TABLE collab_project ADD CONSTRAINT fk_collab_requirement_group FOREIGN KEY (group_id) REFERENCES collab_group(id) ON DELETE RESTRICT;
UPDATE collab_project SET group_id=@legacy_group_id WHERE group_id IS NULL;
ALTER TABLE collab_project MODIFY COLUMN group_id BIGINT NOT NULL;

CREATE TABLE collab_requirement_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    requirement_id BIGINT NOT NULL,
    source_group_role_key VARCHAR(64) NOT NULL,
    role_name_snapshot VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_collab_requirement_role UNIQUE (requirement_id,source_group_role_key),
    CONSTRAINT fk_collab_requirement_role_requirement FOREIGN KEY (requirement_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
    INDEX idx_collab_requirement_role_requirement (requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_requirement_stage_role (
    stage_id BIGINT NOT NULL,
    duty VARCHAR(16) NOT NULL,
    requirement_role_id BIGINT NOT NULL,
    PRIMARY KEY (stage_id,duty),
    CONSTRAINT fk_collab_requirement_stage_role_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_requirement_stage_role_role FOREIGN KEY (requirement_role_id) REFERENCES collab_requirement_role(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collab_requirement_stage_actor (
    stage_id BIGINT NOT NULL,
    duty VARCHAR(16) NOT NULL,
    user_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'INHERITED',
    frozen_at DATETIME(3) NULL,
    PRIMARY KEY (stage_id,duty,user_id),
    CONSTRAINT fk_collab_requirement_stage_actor_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collab_requirement_stage_actor_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_collab_req_stage_actor_user (user_id,duty,stage_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE collab_stage ADD COLUMN actors_frozen_at DATETIME(3) NULL;

INSERT INTO collab_requirement_role(requirement_id,source_group_role_key,role_name_snapshot)
SELECT s.project_id,CONCAT('legacy-',s.project_id,'-',s.stage_key,'-owner'),CONCAT('历史负责人 · ',s.stage_key)
FROM collab_stage s;
INSERT INTO collab_requirement_role(requirement_id,source_group_role_key,role_name_snapshot)
SELECT s.project_id,CONCAT('legacy-',s.project_id,'-',s.stage_key,'-reviewer'),CONCAT('历史审批人 · ',s.stage_key)
FROM collab_stage s;

INSERT INTO collab_requirement_stage_role(stage_id,duty,requirement_role_id)
SELECT s.id,'OWNER',r.id FROM collab_stage s JOIN collab_requirement_role r ON r.requirement_id=s.project_id AND r.source_group_role_key=CONCAT('legacy-',s.project_id,'-',s.stage_key,'-owner');
INSERT INTO collab_requirement_stage_role(stage_id,duty,requirement_role_id)
SELECT s.id,'REVIEWER',r.id FROM collab_stage s JOIN collab_requirement_role r ON r.requirement_id=s.project_id AND r.source_group_role_key=CONCAT('legacy-',s.project_id,'-',s.stage_key,'-reviewer');

UPDATE collab_stage SET actors_frozen_at=NOW(3)
WHERE status NOT IN ('WAITING','READY','NOT_STARTED','SKIPPED');

INSERT INTO collab_requirement_stage_actor(stage_id,duty,user_id,source,frozen_at)
SELECT s.id,'OWNER',s.owner_user_id,'LEGACY',s.actors_frozen_at FROM collab_stage s;
INSERT IGNORE INTO collab_requirement_stage_actor(stage_id,duty,user_id,source,frozen_at)
SELECT s.id,'REVIEWER',s.reviewer_user_id,'LEGACY',s.actors_frozen_at FROM collab_stage s WHERE s.reviewer_user_id IS NOT NULL;
INSERT IGNORE INTO collab_requirement_stage_actor(stage_id,duty,user_id,source,frozen_at)
SELECT s.id,'REVIEWER',ar.reviewer_user_id,'LEGACY',s.actors_frozen_at FROM collab_stage_additional_reviewer ar JOIN collab_stage s ON s.id=ar.stage_id;

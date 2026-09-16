CREATE TABLE virtual_project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_key CHAR(36) NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_virtual_project_key UNIQUE (project_key),
    CONSTRAINT fk_virtual_project_creator FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_virtual_project_status (status, time_updated)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE virtual_project_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_key VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_virtual_project_member UNIQUE (project_id, user_id),
    CONSTRAINT fk_project_member_project FOREIGN KEY (project_id) REFERENCES virtual_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_member_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_member_creator FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_project_member_user (user_id, status),
    INDEX idx_project_member_project (project_id, status, role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    project_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    current_draft_revision_id BIGINT NULL,
    published_revision_id BIGINT NULL,
    ever_published BOOLEAN NOT NULL DEFAULT FALSE,
    last_draft_activity_at DATETIME(3) NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_project_document_project FOREIGN KEY (project_id) REFERENCES virtual_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_document_creator FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_document_updater FOREIGN KEY (updated_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_project_document_project (project_id, status, document_type, time_updated),
    INDEX idx_project_document_cleanup (ever_published, status, last_draft_activity_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_document_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    document_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    markdown_content MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    profile_key VARCHAR(64) NULL,
    agent_session_id VARCHAR(128) NULL,
    agent_job_id VARCHAR(128) NULL,
    skill_snapshots JSON NULL,
    assumptions JSON NULL,
    open_questions JSON NULL,
    created_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_project_document_revision UNIQUE (document_id, revision_no),
    CONSTRAINT fk_project_revision_document FOREIGN KEY (document_id) REFERENCES project_document(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_revision_creator FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_project_revision_document (document_id, revision_no),
    INDEX idx_project_revision_agent (agent_session_id, agent_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE project_document
    ADD CONSTRAINT fk_project_document_draft_revision FOREIGN KEY (current_draft_revision_id) REFERENCES project_document_revision(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_project_document_published_revision FOREIGN KEY (published_revision_id) REFERENCES project_document_revision(id) ON DELETE RESTRICT;

CREATE TABLE project_document_source (
    document_id BIGINT NOT NULL,
    source_document_id BIGINT NOT NULL,
    source_revision_id BIGINT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'DERIVED_FROM',
    PRIMARY KEY (document_id, source_document_id),
    CONSTRAINT fk_project_source_document FOREIGN KEY (document_id) REFERENCES project_document(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_source_source_document FOREIGN KEY (source_document_id) REFERENCES project_document(id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_source_revision FOREIGN KEY (source_revision_id) REFERENCES project_document_revision(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO iam_permission (permission_key, permission_name, description, time_created, time_updated)
VALUES
 ('project:browse','浏览项目组','浏览所属虚拟项目组和文档',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('project:create','创建项目组','创建虚拟项目组',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('project:manage','管理项目组','管理项目成员和项目状态',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
 ('project:document:publish','发布项目文档','发布项目正式文档版本',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), description=VALUES(description), time_updated=CURRENT_TIMESTAMP(6);

CREATE TABLE skill_category (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    category_key VARCHAR(64) NOT NULL, category_name VARCHAR(128) NOT NULL, parent_id BIGINT NULL,
    sort_order INT NOT NULL, status VARCHAR(32) NOT NULL, version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id), CONSTRAINT uk_category_key UNIQUE (category_key),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES skill_category(id) ON DELETE RESTRICT,
    INDEX idx_category_parent(parent_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_tag (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    tag_key VARCHAR(64) NOT NULL, tag_name VARCHAR(128) NOT NULL,
    PRIMARY KEY (id), CONSTRAINT uk_tag_key UNIQUE (tag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_key VARCHAR(64) NOT NULL, display_name VARCHAR(128) NOT NULL, description VARCHAR(1024) NOT NULL,
    category_id BIGINT NULL, status VARCHAR(32) NOT NULL, latest_published_version_id BIGINT NULL,
    active_draft_version_id BIGINT NULL, version_no INT NOT NULL DEFAULT 0, created_by BIGINT NOT NULL, updated_by BIGINT NOT NULL,
    PRIMARY KEY (id), CONSTRAINT uk_skill_key UNIQUE (skill_key),
    CONSTRAINT fk_skill_category FOREIGN KEY (category_id) REFERENCES skill_category(id) ON DELETE RESTRICT,
    CONSTRAINT fk_skill_creator FOREIGN KEY (created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_skill_updater FOREIGN KEY (updated_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    INDEX idx_skill_category_status(category_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_version (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_id BIGINT NOT NULL, base_version_id BIGINT NULL, change_type VARCHAR(32) NOT NULL,
    candidate_version VARCHAR(32) NULL, version VARCHAR(32) NULL, lifecycle_status VARCHAR(32) NOT NULL,
    source_revision INT NOT NULL, source_object_key VARCHAR(512) NOT NULL, source_sha256 CHAR(64) NOT NULL,
    source_size_bytes BIGINT NOT NULL, manifest_json JSON NOT NULL, change_log TEXT NULL,
    replacement_version_id BIGINT NULL, published_at DATETIME(3) NULL, offline_at DATETIME(3) NULL,
    version_no INT NOT NULL DEFAULT 0, created_by BIGINT NOT NULL, updated_by BIGINT NOT NULL,
    PRIMARY KEY (id), CONSTRAINT uk_skill_version UNIQUE(skill_id, version),
    CONSTRAINT uk_skill_candidate_version UNIQUE(skill_id, candidate_version),
    CONSTRAINT fk_version_skill FOREIGN KEY(skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_version_base FOREIGN KEY(base_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_version_replacement FOREIGN KEY(replacement_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_version_creator FOREIGN KEY(created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_version_updater FOREIGN KEY(updated_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_version_source_size CHECK(source_size_bytes >= 0),
    INDEX idx_skill_version_status(skill_id, lifecycle_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE skill ADD CONSTRAINT fk_skill_latest_version FOREIGN KEY(latest_published_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT;
ALTER TABLE skill ADD CONSTRAINT fk_skill_active_draft FOREIGN KEY(active_draft_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT;

CREATE TABLE skill_owner (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_id BIGINT NOT NULL, user_id BIGINT NOT NULL, owner_type VARCHAR(32) NOT NULL,
    PRIMARY KEY(id), CONSTRAINT uk_skill_owner UNIQUE(skill_id,user_id),
    CONSTRAINT fk_owner_skill FOREIGN KEY(skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_owner_user FOREIGN KEY(user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_tag_relation (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, PRIMARY KEY(id),
    CONSTRAINT uk_skill_tag UNIQUE(skill_id,tag_id),
    CONSTRAINT fk_skill_tag_skill FOREIGN KEY(skill_id) REFERENCES skill(id) ON DELETE RESTRICT,
    CONSTRAINT fk_skill_tag_tag FOREIGN KEY(tag_id) REFERENCES skill_tag(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_source_revision (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_version_id BIGINT NOT NULL, revision_no INT NOT NULL, object_key VARCHAR(512) NOT NULL,
    sha256 CHAR(64) NOT NULL, size_bytes BIGINT NOT NULL, change_summary VARCHAR(1024) NULL, created_by BIGINT NOT NULL,
    PRIMARY KEY(id), CONSTRAINT uk_source_revision UNIQUE(skill_version_id,revision_no),
    CONSTRAINT fk_revision_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_revision_creator FOREIGN KEY(created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_revision_size CHECK(size_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_file_index (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_version_id BIGINT NOT NULL, source_revision INT NOT NULL, relative_path VARCHAR(1024) NOT NULL,
    path_hash CHAR(64) NOT NULL, file_type VARCHAR(32) NOT NULL, media_type VARCHAR(128) NULL,
    size_bytes BIGINT NOT NULL, sha256 CHAR(64) NULL, editable TINYINT(1) NOT NULL, file_source VARCHAR(32) NOT NULL,
    PRIMARY KEY(id), CONSTRAINT uk_version_revision_path_hash UNIQUE(skill_version_id,source_revision,path_hash),
    CONSTRAINT fk_file_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    INDEX idx_file_tree(skill_version_id,source_revision,file_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

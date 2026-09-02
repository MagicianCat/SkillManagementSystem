CREATE TABLE skill_version_dependency (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_version_id BIGINT NOT NULL, dependency_skill_id BIGINT NOT NULL, version_constraint VARCHAR(128) NOT NULL,
    dependency_type VARCHAR(32) NOT NULL, required TINYINT(1) NOT NULL, sort_order INT NOT NULL,
    PRIMARY KEY(id), CONSTRAINT uk_version_dependency UNIQUE(skill_version_id,dependency_skill_id,dependency_type),
    CONSTRAINT fk_dependency_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_dependency_skill FOREIGN KEY(dependency_skill_id) REFERENCES skill(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_version_lock (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    root_skill_version_id BIGINT NOT NULL, lock_hash CHAR(64) NOT NULL, resolver_version VARCHAR(32) NOT NULL, lock_json JSON NOT NULL,
    PRIMARY KEY(id), CONSTRAINT uk_version_lock UNIQUE(root_skill_version_id,lock_hash),
    CONSTRAINT fk_lock_root FOREIGN KEY(root_skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_version_lock_item (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    lock_id BIGINT NOT NULL, skill_version_id BIGINT NOT NULL, depth INT NOT NULL,
    required_by_path VARCHAR(2048) NOT NULL, resolved_version VARCHAR(32) NOT NULL, sha256 CHAR(64) NOT NULL,
    PRIMARY KEY(id), CONSTRAINT uk_lock_skill UNIQUE(lock_id,skill_version_id),
    CONSTRAINT fk_lock_item_lock FOREIGN KEY(lock_id) REFERENCES skill_version_lock(id) ON DELETE RESTRICT,
    CONSTRAINT fk_lock_item_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    platform_key VARCHAR(64) NOT NULL, platform_name VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL,
    user_install_path_template VARCHAR(512) NULL, project_install_path_template VARCHAR(512) NULL,
    version_no INT NOT NULL DEFAULT 0, PRIMARY KEY(id), CONSTRAINT uk_platform_key UNIQUE(platform_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_agent (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    platform_id BIGINT NOT NULL, agent_key VARCHAR(64) NOT NULL, agent_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL, is_default TINYINT(1) NOT NULL, version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY(id), CONSTRAINT uk_platform_agent UNIQUE(platform_id,agent_key),
    CONSTRAINT fk_agent_platform FOREIGN KEY(platform_id) REFERENCES platform(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE platform_adapter (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    platform_id BIGINT NOT NULL, adapter_version VARCHAR(64) NOT NULL, implementation_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL, configuration_json JSON NOT NULL, version_no INT NOT NULL DEFAULT 0,
    PRIMARY KEY(id), CONSTRAINT uk_platform_adapter UNIQUE(platform_id,adapter_version),
    CONSTRAINT fk_adapter_platform FOREIGN KEY(platform_id) REFERENCES platform(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skill_version_compatibility (
    id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
    skill_version_id BIGINT NOT NULL, platform_id BIGINT NOT NULL, agent_id BIGINT NULL, os_type VARCHAR(32) NOT NULL,
    required TINYINT(1) NOT NULL, declared_status VARCHAR(32) NOT NULL, validated_status VARCHAR(32) NULL,
    min_platform_version VARCHAR(64) NULL, max_platform_version VARCHAR(64) NULL, overlay_path VARCHAR(512) NULL,
    inherited_default TINYINT(1) NOT NULL, validation_message TEXT NULL, validated_at DATETIME(3) NULL,
    agent_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(agent_id,0)) STORED,
    PRIMARY KEY(id), CONSTRAINT uk_version_platform_agent_os UNIQUE(skill_version_id,platform_id,agent_scope_id,os_type),
    CONSTRAINT fk_compat_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_compat_platform FOREIGN KEY(platform_id) REFERENCES platform(id) ON DELETE RESTRICT,
    CONSTRAINT fk_compat_agent FOREIGN KEY(agent_id) REFERENCES platform_agent(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO platform(time_created,time_updated,platform_key,platform_name,status,user_install_path_template,project_install_path_template,version_no)
VALUES (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'CODEBUDDY','CodeBuddy','ACTIVE',NULL,NULL,0),
       (UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OPENCODE','OpenCode','ACTIVE',NULL,NULL,0);
INSERT INTO platform_agent(time_created,time_updated,platform_id,agent_key,agent_name,status,is_default,version_no)
SELECT UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),id,CONCAT(platform_key,'_DEFAULT'),CONCAT(platform_name,' Default'),'ACTIVE',1,0 FROM platform;
INSERT INTO platform_adapter(time_created,time_updated,platform_id,adapter_version,implementation_key,status,configuration_json,version_no)
SELECT UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),id,'1.0.0',CONCAT(LOWER(platform_key),'-adapter'),'ACTIVE',JSON_OBJECT(),0 FROM platform;

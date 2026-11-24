ALTER TABLE agent_run_intent_plan
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1,
    ADD COLUMN retrieval_plan_json JSON NULL,
    ADD COLUMN model_advice_json JSON NULL,
    ADD COLUMN plan_revision INT NOT NULL DEFAULT 0;

CREATE TABLE agent_run_retrieval_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_key VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    evidence_kind VARCHAR(32) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    version_key VARCHAR(128) NOT NULL DEFAULT '',
    audience VARCHAR(16) NOT NULL DEFAULT '',
    metadata_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_run_evidence(run_key,task_id,evidence_kind,object_key,version_key,audience),
    KEY ix_run_evidence_run(run_key,id)
);

CREATE TABLE external_role_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    alias VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    UNIQUE KEY uk_role_alias(role_id,normalized_alias),
    KEY ix_role_alias(normalized_alias,status),
    CONSTRAINT fk_role_alias_role FOREIGN KEY(role_id) REFERENCES external_role(id)
);

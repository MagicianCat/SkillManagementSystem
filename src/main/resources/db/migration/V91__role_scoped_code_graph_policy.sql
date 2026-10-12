-- Role-scoped code graph policy, frozen per workflow run.
-- Existing runs remain compatible through the backend legacy/default resolver.

CREATE TABLE project_agent_code_graph_policy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    configuration_id BIGINT NOT NULL,
    stage_key VARCHAR(100) NOT NULL,
    node_key VARCHAR(100) NOT NULL,
    policy_json JSON NOT NULL,
    policy_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_agent_graph_policy (configuration_id, stage_key, node_key),
    CONSTRAINT fk_project_agent_graph_policy_config FOREIGN KEY (configuration_id)
        REFERENCES project_agent_configuration(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE workflow_run_code_graph_policy_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    stage_key VARCHAR(100) NOT NULL,
    node_key VARCHAR(100) NOT NULL,
    profile_version_id BIGINT NOT NULL,
    policy_json JSON NOT NULL,
    policy_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_graph_policy_node (workflow_run_id, stage_key, node_key),
    CONSTRAINT fk_run_graph_policy_run FOREIGN KEY (workflow_run_id)
        REFERENCES workflow_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE code_graph_budget_counter (
    stage_run_id BIGINT NOT NULL,
    node_key VARCHAR(100) NOT NULL,
    stage_tool_calls INT NOT NULL DEFAULT 0,
    node_tool_calls INT NOT NULL DEFAULT 0,
    stage_engine_queries INT NOT NULL DEFAULT 0,
    node_engine_queries INT NOT NULL DEFAULT 0,
    preload_queries INT NOT NULL DEFAULT 0,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (stage_run_id, node_key),
    CONSTRAINT fk_graph_budget_stage FOREIGN KEY (stage_run_id)
        REFERENCES stage_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE code_graph_tool_invocation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    invocation_key VARCHAR(128) NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    stage_run_id BIGINT NOT NULL,
    agent_run_id BIGINT NOT NULL,
    node_key VARCHAR(100) NOT NULL,
    graph_binding_id BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL,
    query_hash CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    engine_executed BOOLEAN NOT NULL DEFAULT FALSE,
    preload BOOLEAN NOT NULL DEFAULT FALSE,
    returned_chars INT NOT NULL DEFAULT 0,
    result_count INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NULL,
    error_code VARCHAR(100) NULL,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_graph_invocation_key (invocation_key),
    KEY idx_graph_invocation_stage (stage_run_id, operation, status),
    KEY idx_graph_invocation_node (stage_run_id, node_key, status),
    CONSTRAINT fk_graph_invocation_run FOREIGN KEY (workflow_run_id)
        REFERENCES workflow_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_invocation_stage FOREIGN KEY (stage_run_id)
        REFERENCES stage_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_invocation_agent FOREIGN KEY (agent_run_id)
        REFERENCES agent_workflow_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE code_graph_query_cache (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    graph_binding_id BIGINT NOT NULL,
    query_hash CHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    result_json JSON NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_graph_query_cache (workflow_run_id, graph_binding_id, query_hash),
    CONSTRAINT fk_graph_query_cache_run FOREIGN KEY (workflow_run_id)
        REFERENCES workflow_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE workflow_code_graph_evidence_pack (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    graph_binding_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    pack_json JSON NOT NULL,
    pack_hash CHAR(64) NOT NULL,
    updated_by_agent_run_id BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_graph_evidence_revision (workflow_run_id, graph_binding_id, revision_no),
    KEY idx_graph_evidence_latest (workflow_run_id, graph_binding_id, revision_no),
    CONSTRAINT fk_graph_evidence_run FOREIGN KEY (workflow_run_id)
        REFERENCES workflow_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_evidence_agent FOREIGN KEY (updated_by_agent_run_id)
        REFERENCES agent_workflow_run(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

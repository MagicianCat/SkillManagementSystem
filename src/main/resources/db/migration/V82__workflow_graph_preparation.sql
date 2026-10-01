ALTER TABLE workflow_run
    ADD COLUMN prepared_at DATETIME(3) NULL AFTER started_at,
    ADD COLUMN activated_at DATETIME(3) NULL AFTER prepared_at,
    ADD COLUMN code_graph_required BOOLEAN NOT NULL DEFAULT FALSE AFTER activated_at;

ALTER TABLE workflow_run_git_repository
    ADD COLUMN resolved_commit_sha CHAR(40) NULL AFTER tracked_branch,
    ADD COLUMN resolved_tree_sha CHAR(40) NULL AFTER resolved_commit_sha,
    ADD COLUMN resolved_at DATETIME(3) NULL AFTER resolved_tree_sha,
    ADD COLUMN logical_repository_key VARCHAR(300) NULL AFTER resolved_at,
    ADD KEY idx_workflow_git_repository_frozen (workflow_run_id, logical_repository_key, resolved_tree_sha);

ALTER TABLE agent_workflow_run
    ADD COLUMN code_graph_binding_id BIGINT NULL AFTER profile_version_id,
    ADD KEY idx_agent_workflow_code_graph_binding (code_graph_binding_id),
    ADD CONSTRAINT fk_agent_workflow_code_graph_binding
        FOREIGN KEY (code_graph_binding_id) REFERENCES workflow_run_code_graph_binding(id);

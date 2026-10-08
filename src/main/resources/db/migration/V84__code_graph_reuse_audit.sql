ALTER TABLE code_graph_repository_snapshot
    ADD COLUMN commit_distance INT NULL AFTER base_snapshot_id,
    ADD KEY idx_code_graph_snapshot_ancestor (
        logical_repository_key,
        status,
        engine_type,
        engine_version,
        adapter_version,
        engine_config_hash,
        commit_sha
    );

ALTER TABLE code_graph_bundle_repository
    ADD COLUMN build_mode VARCHAR(24) NOT NULL DEFAULT 'FULL' AFTER repository_alias,
    ADD COLUMN base_snapshot_id BIGINT NULL AFTER build_mode,
    ADD KEY idx_code_graph_bundle_repo_base (base_snapshot_id),
    ADD CONSTRAINT fk_code_graph_bundle_repo_base FOREIGN KEY (base_snapshot_id)
        REFERENCES code_graph_repository_snapshot(id);

-- M7 concurrency and semantic sidecar safety.
ALTER TABLE code_graph_bundle
    ADD COLUMN semantic_cleanup_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER last_error,
    ADD COLUMN semantic_points_deleted_at DATETIME(3) NULL AFTER semantic_cleanup_status,
    ADD KEY idx_code_graph_bundle_semantic_cleanup (semantic_cleanup_status, time_updated);

-- MySQL permits multiple NULL values, so this generated key enforces at most one
-- PENDING merge bucket per workflow while allowing unlimited historical rows.
ALTER TABLE code_graph_update_request
    ADD COLUMN pending_workflow_run_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN workflow_run_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_code_graph_update_one_pending (pending_workflow_run_id);

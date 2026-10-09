-- M7: REPO_APPEND update requests.
--
-- Tracks "append repositories to a running workflow" requests. When the user appends
-- repositories while a workflow is RUNNING, we record a PENDING update request that
-- is later merged into a single REPO_APPEND generation job. This decouples the
-- user-facing "append" action from the underlying build pipeline, so consecutive
-- appends can be merged without spamming builds.
--
-- Lifecycle: PENDING -> BUILDING (materialised into a REPO_APPEND job) -> READY
--            PENDING -> BUILDING -> FAILED (retryable)
--            PENDING -> CANCELLED (superseded by a newer append request before build)

CREATE TABLE code_graph_update_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    -- Cumulative target repository set hash after this append. Used to dedupe
    -- consecutive appends that converge to the same target.
    target_repository_set_hash CHAR(64) NOT NULL,
    target_repository_count INT NOT NULL,
    triggered_by BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(100) NULL,
    last_error_message TEXT NULL,
    -- IDs of workflow_run_git_repository rows appended in this request, in order.
    -- The M7 coordinator replays this delta onto the current frozen set at
    -- materialisation time to compute the final repository list.
    appended_workflow_repository_ids_json JSON NOT NULL,
    -- The REPO_APPEND generation job materialised from this request, if any.
    generation_job_id BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_code_graph_update_run (workflow_run_id, status, time_created),
    KEY idx_code_graph_update_status (status, time_created),
    CONSTRAINT fk_code_graph_update_run FOREIGN KEY (workflow_run_id) REFERENCES workflow_run(id),
    CONSTRAINT fk_code_graph_update_job FOREIGN KEY (generation_job_id) REFERENCES code_graph_generation_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Distinguish initial-preparation jobs from in-flight REPO_APPEND jobs so the
-- coordinator can apply different concurrency rules (one active REPO_APPEND per
-- workflow run) without guessing from reason strings.
ALTER TABLE code_graph_generation_job
    ADD COLUMN job_type VARCHAR(24) NOT NULL DEFAULT 'INITIAL' AFTER reason,
    ADD KEY idx_code_graph_job_type (workflow_run_id, job_type, status);

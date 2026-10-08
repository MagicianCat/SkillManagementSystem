ALTER TABLE workflow_run_git_repository
    ADD COLUMN source_artifact_uri VARCHAR(2000) NULL AFTER logical_repository_key,
    ADD COLUMN source_sha256 CHAR(64) NULL AFTER source_artifact_uri;

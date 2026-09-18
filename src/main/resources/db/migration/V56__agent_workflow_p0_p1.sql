ALTER TABLE workflow_run ADD COLUMN initial_request MEDIUMTEXT NULL, ADD COLUMN context_snapshot_json JSON NULL;
CREATE TABLE workflow_artifact_binding (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT, agent_run_id BIGINT, artifact_kind VARCHAR(50) NOT NULL, project_document_id BIGINT NOT NULL, project_document_revision_id BIGINT NOT NULL, relation_type VARCHAR(30) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_workflow_artifact_binding(agent_run_id,project_document_revision_id,relation_type), INDEX idx_workflow_artifact_latest(workflow_run_id,artifact_kind,relation_type), CONSTRAINT fk_binding_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_runtime_event (
 id BIGINT NOT NULL AUTO_INCREMENT, event_id VARCHAR(160) NOT NULL, workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT, agent_session_id BIGINT, agent_run_id BIGINT, event_seq BIGINT NOT NULL DEFAULT 0, event_type VARCHAR(80) NOT NULL, payload_json JSON NOT NULL, time_created DATETIME(3) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_workflow_runtime_event(event_id), INDEX idx_workflow_runtime_event_cursor(workflow_run_id,event_seq), CONSTRAINT fk_runtime_event_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE runtime_command ADD COLUMN lease_until DATETIME(3) NULL, ADD COLUMN target_runtime_id VARCHAR(200) NULL, ADD COLUMN target_conversation_id VARCHAR(200) NULL, ADD COLUMN target_run_id BIGINT NULL;
ALTER TABLE human_intervention ADD COLUMN runtime_command_id BIGINT NULL, ADD COLUMN agent_session_id BIGINT NULL;

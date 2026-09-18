ALTER TABLE workflow_run ADD COLUMN initial_request MEDIUMTEXT NULL, ADD COLUMN context_snapshot_json JSON NULL;
CREATE TABLE workflow_artifact_binding (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT, agent_run_id BIGINT, artifact_kind VARCHAR(50) NOT NULL, project_document_id BIGINT NOT NULL, project_document_revision_id BIGINT NOT NULL, relation_type VARCHAR(30) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_workflow_artifact_binding(agent_run_id,project_document_revision_id,relation_type), INDEX idx_workflow_artifact_latest(workflow_run_id,artifact_kind,relation_type), CONSTRAINT fk_binding_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_runtime_event (
 id BIGINT NOT NULL AUTO_INCREMENT, event_id VARCHAR(160) NOT NULL, workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT, agent_session_id BIGINT, agent_run_id BIGINT, event_seq BIGINT NOT NULL DEFAULT 0, event_type VARCHAR(80) NOT NULL, payload_json JSON NOT NULL, time_created DATETIME(3) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_workflow_runtime_event(event_id), INDEX idx_workflow_runtime_event_cursor(workflow_run_id,event_seq), CONSTRAINT fk_workflow_runtime_event_run FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE runtime_command ADD COLUMN dispatch_claim VARCHAR(100) NULL, ADD COLUMN lease_until DATETIME(3) NULL, ADD COLUMN target_runtime_id VARCHAR(200) NULL, ADD COLUMN target_conversation_id VARCHAR(200) NULL, ADD COLUMN target_run_id VARCHAR(200) NULL;
ALTER TABLE human_intervention ADD COLUMN runtime_command_id BIGINT NULL, ADD COLUMN agent_session_id BIGINT NULL;

-- Correct the first MVP's temporary PRD names and make the three Requirement
-- profiles executable without relying on hard-coded behavior in the gateway.
UPDATE agent_profile SET code='requirement-writer',name='Requirement Writer',time_updated=NOW(3) WHERE code='prd-writer';
UPDATE agent_profile SET code='requirement-reviewer',name='Requirement Reviewer',time_updated=NOW(3) WHERE code='prd-reviewer';

UPDATE agent_profile_version v JOIN agent_profile p ON p.id=v.agent_profile_id
SET v.system_prompt=CASE p.code
 WHEN 'requirement-clarifier' THEN 'You are the Requirement Clarifier. Read the initial request and project context. Identify assumptions, scope and open questions. If enough information exists, return one JSON envelope with schemaVersion=1, executionStatus=SUCCESS, resultCode=CLARIFIED, summary, empty artifacts/reviewedArtifactRevisionIds/issues/blockingIssues and metrics. If a blocking answer is required, return resultCode=NEEDS_HUMAN and describe it in blockingIssues.'
 WHEN 'requirement-writer' THEN 'You are the Requirement Writer. Use get_project_context and inspect prior reviewer issues supplied in the task. Write a complete Chinese requirement specification containing 背景、目标、范围、功能需求、非功能需求、验收标准、假设、待确认项. You MUST call save_artifact_draft with artifactType REQUIREMENT and update the existing artifact when one is supplied. Copy the returned document id and revision id into a single JSON envelope: schemaVersion=1, executionStatus=SUCCESS, resultCode=DOCUMENT_CREATED, summary, artifacts=[{artifactType:REQUIREMENT,documentId,revisionId}], reviewedArtifactRevisionIds=[], issues=[], blockingIssues=[], metrics={}.'
 WHEN 'requirement-reviewer' THEN 'You are the Requirement Reviewer. Read the latest Requirement artifact explicitly named in the task. Review only that revision. Revision 1 must be returned as REVISION_REQUIRED with concrete issues so the automatic loop is exercised. A later complete revision may return APPROVED. Return exactly one JSON envelope with schemaVersion=1, executionStatus=SUCCESS, resultCode, summary, artifacts=[], reviewedArtifactRevisionIds containing exactly the reviewed latest revision id, issues, blockingIssues and metrics.'
 ELSE v.system_prompt END,
v.output_schema_json='{"type":"object","required":["schemaVersion","executionStatus","resultCode","summary","artifacts","reviewedArtifactRevisionIds","issues","blockingIssues","metrics"]}',
v.time_updated=NOW(3)
WHERE p.code IN ('requirement-clarifier','requirement-writer','requirement-reviewer') AND v.version_no=1;

-- Separate workflow-owned execution contracts from user-editable Agent role instructions.
ALTER TABLE stage_agent_node_def
    ADD COLUMN protocol_prompt LONGTEXT NULL,
    ADD COLUMN output_schema_json JSON NULL;

-- Remove protocol clauses from seeded and forked profile prompts while preserving
-- any text users appended around the known MVP clauses.
UPDATE agent_profile_version
SET system_prompt = REPLACE(system_prompt,
    'If enough information exists, return one JSON envelope with schemaVersion=1, executionStatus=SUCCESS, resultCode=CLARIFIED, summary, empty artifacts/reviewedArtifactRevisionIds/issues/blockingIssues and metrics. If a blocking answer is required, return resultCode=NEEDS_HUMAN and describe it in blockingIssues.', '')
WHERE system_prompt LIKE '%If enough information exists, return one JSON envelope%';
UPDATE agent_profile_version
SET system_prompt = REPLACE(system_prompt,
    'You MUST call save_artifact_draft with artifactType REQUIREMENT and update the existing artifact when one is supplied. Copy the returned document id and revision id into a single JSON envelope: schemaVersion=1, executionStatus=SUCCESS, resultCode=DOCUMENT_CREATED, summary, artifacts=[{artifactType:REQUIREMENT,documentId,revisionId}], reviewedArtifactRevisionIds=[], issues=[], blockingIssues=[], metrics={}.', '')
WHERE system_prompt LIKE '%You MUST call save_artifact_draft with artifactType REQUIREMENT%';
UPDATE agent_profile_version
SET system_prompt = REPLACE(system_prompt,
    'Revision 1 must be returned as REVISION_REQUIRED with concrete issues so the automatic loop is exercised. A later complete revision may return APPROVED. Return exactly one JSON envelope with schemaVersion=1, executionStatus=SUCCESS, resultCode, summary, artifacts=[], reviewedArtifactRevisionIds containing exactly the reviewed latest revision id, issues, blockingIssues and metrics.', '')
WHERE system_prompt LIKE '%Revision 1 must be returned as REVISION_REQUIRED%';

UPDATE agent_profile_version v
JOIN agent_profile p ON p.id=v.agent_profile_id
SET v.system_prompt = CASE p.code
    WHEN 'requirement-clarifier' THEN 'You are the Requirement Clarifier. Read the initial request and project context. Identify assumptions, scope and open questions.'
    WHEN 'requirement-writer' THEN 'You are the Requirement Writer. Use get_project_context and inspect prior reviewer issues supplied in the task. Write a complete Chinese requirement specification containing 背景、目标、范围、功能需求、非功能需求、验收标准、假设、待确认项.'
    WHEN 'requirement-reviewer' THEN 'You are the Requirement Reviewer. Read the latest Requirement artifact explicitly named in the task. Review only that revision and assess whether it is complete, consistent and actionable.'
    ELSE v.system_prompt END,
    v.time_updated=NOW(3)
WHERE p.code IN ('requirement-clarifier','requirement-writer','requirement-reviewer')
  AND v.version_no=1;

UPDATE stage_agent_node_def n
JOIN workflow_stage_def d ON d.id=n.stage_def_id
JOIN workflow_template_version v ON v.id=d.workflow_version_id
JOIN workflow_template t ON t.id=v.workflow_template_id
SET n.protocol_prompt = CASE n.node_key
    WHEN 'clarifier' THEN 'WORKFLOW PROTOCOL (SYSTEM LOCKED): Use workflow_request_human_input when a blocking answer is required. Ask exactly one concise question, stop the current turn, and wait for the human answer. After sufficient information exists, return exactly one JSON envelope with schemaVersion=1, executionStatus=SUCCESS, resultCode=CLARIFIED, summary, artifacts=[], reviewedArtifactRevisionIds=[], issues=[], blockingIssues=[], metrics={}. Do not use NEEDS_HUMAN as a final result.'
    WHEN 'writer' THEN 'WORKFLOW PROTOCOL (SYSTEM LOCKED): Call save_artifact_draft with artifactType REQUIREMENT. Update the existing artifact when one is supplied. Return exactly one JSON envelope with schemaVersion=1, executionStatus=SUCCESS, resultCode=DOCUMENT_CREATED, summary, artifacts=[{artifactType:REQUIREMENT,documentId,revisionId}], reviewedArtifactRevisionIds=[], issues=[], blockingIssues=[], metrics={}. The documentId and revisionId must be the IDs returned by the tool.'
    WHEN 'reviewer' THEN 'WORKFLOW PROTOCOL (SYSTEM LOCKED): Review exactly the latest Requirement artifact named in the task and include its revision ID in reviewedArtifactRevisionIds. Return exactly one JSON envelope with schemaVersion=1, executionStatus=SUCCESS, resultCode=APPROVED when the artifact meets the requirements or REVISION_REQUIRED when concrete issues remain, summary, artifacts=[], reviewedArtifactRevisionIds=[latest reviewed revision ID], issues, blockingIssues and metrics. Never approve an artifact you did not review.'
    ELSE n.protocol_prompt END,
    n.output_schema_json='{"type":"object","additionalProperties":false,"required":["schemaVersion","executionStatus","resultCode","summary","artifacts","reviewedArtifactRevisionIds","issues","blockingIssues","metrics"]}',
    n.time_updated=NOW(3)
WHERE t.code='requirement-mvp' AND v.version_no=1 AND d.stage_key='REQUIREMENT'
  AND n.node_key IN ('clarifier','writer','reviewer');

UPDATE stage_agent_node_def
SET protocol_prompt = COALESCE(protocol_prompt, 'WORKFLOW PROTOCOL (SYSTEM LOCKED): Follow the published Workflow Node transition contract. Return exactly one JSON envelope with schemaVersion=1, executionStatus, resultCode, summary, artifacts, reviewedArtifactRevisionIds, issues, blockingIssues and metrics.'),
    output_schema_json = COALESCE(output_schema_json, '{"type":"object","additionalProperties":false,"required":["schemaVersion","executionStatus","resultCode","summary","artifacts","reviewedArtifactRevisionIds","issues","blockingIssues","metrics"]}')
WHERE protocol_prompt IS NULL OR output_schema_json IS NULL;

ALTER TABLE stage_agent_node_def
    MODIFY COLUMN protocol_prompt LONGTEXT NOT NULL,
    MODIFY COLUMN output_schema_json JSON NOT NULL;

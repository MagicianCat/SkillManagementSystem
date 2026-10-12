-- Workflow identity, artifact IDs, revision IDs and runtime status are backend facts.
-- Agents now return only semantic completion data.

UPDATE workflow_stage_def d
JOIN workflow_template_version v ON v.id=d.workflow_version_id
JOIN workflow_template t ON t.id=v.workflow_template_id
SET d.artifact_type='REQUIREMENT',d.time_updated=NOW(3)
WHERE t.code='requirement-mvp' AND d.stage_key='REQUIREMENT';

UPDATE stage_agent_node_def n
JOIN workflow_stage_def d ON d.id=n.stage_def_id
JOIN workflow_template_version v ON v.id=d.workflow_version_id
JOIN workflow_template t ON t.id=v.workflow_template_id
SET n.workflow_role=CASE n.node_key
      WHEN 'clarifier' THEN 'CLARIFIER'
      WHEN 'writer' THEN 'AUTHOR'
      WHEN 'reviewer' THEN 'REVIEWER'
      ELSE n.workflow_role END,
    n.time_updated=NOW(3)
WHERE t.code='requirement-mvp';

UPDATE stage_agent_node_def n
JOIN workflow_stage_def d ON d.id=n.stage_def_id
JOIN workflow_template_version v ON v.id=d.workflow_version_id
JOIN workflow_template t ON t.id=v.workflow_template_id
SET n.protocol_prompt=CASE n.workflow_role
      WHEN 'CLARIFIER' THEN
        'WORKFLOW PROTOCOL (SYSTEM LOCKED): When blocking information is required, call workflow_request_human_input with one concise question and stop. Otherwise call the built-in finish tool exactly once with ONLY one JSON object: {"resultCode":"CLARIFIED","summary":"non-empty summary"}. Workflow identity and status are supplied by the platform; do not return database IDs, artifacts, revision IDs, executionStatus, schemaVersion, metrics, or blockingIssues.'
      WHEN 'ANALYST' THEN
        'WORKFLOW PROTOCOL (SYSTEM LOCKED): Analyze every immutable upstream revision supplied by the platform. Do not create a stage artifact. When blocked, call workflow_request_human_input with one concise question and stop. Otherwise call the built-in finish tool exactly once with ONLY one JSON object: {"resultCode":"ANALYZED","summary":"non-empty summary"}. Do not return database IDs or platform state.'
      WHEN 'AUTHOR' THEN
        CONCAT('WORKFLOW PROTOCOL (SYSTEM LOCKED): Produce the complete ',d.artifact_type,' document and call save_artifact_draft. The platform fixes the project, stage, artifact type, update target, provenance, document ID and revision ID; do not choose or repeat those values. After the tool succeeds, call the built-in finish tool exactly once with ONLY one JSON object: {"resultCode":"DOCUMENT_CREATED","summary":"non-empty summary"}. Do not return artifacts, database IDs, executionStatus, schemaVersion, metrics, or blockingIssues.')
      WHEN 'REVIEWER' THEN
        CONCAT('WORKFLOW PROTOCOL (SYSTEM LOCKED): Review exactly the immutable ',d.artifact_type,' revision pinned by the platform. Return the semantic decision only. Call the built-in finish tool exactly once with ONLY one JSON object containing resultCode=APPROVED or REVISION_REQUIRED, a non-empty summary, and issues=[]. Each issue should contain severity, code and message. Do not return or repeat document IDs, revision IDs, artifacts, executionStatus, schemaVersion, metrics, or blockingIssues.')
      ELSE n.protocol_prompt END,
    n.output_schema_json=CASE n.workflow_role
      WHEN 'CLARIFIER' THEN '{"type":"object","additionalProperties":false,"required":["resultCode","summary"],"properties":{"resultCode":{"const":"CLARIFIED"},"summary":{"type":"string","minLength":1}}}'
      WHEN 'ANALYST' THEN '{"type":"object","additionalProperties":false,"required":["resultCode","summary"],"properties":{"resultCode":{"const":"ANALYZED"},"summary":{"type":"string","minLength":1}}}'
      WHEN 'AUTHOR' THEN '{"type":"object","additionalProperties":false,"required":["resultCode","summary"],"properties":{"resultCode":{"const":"DOCUMENT_CREATED"},"summary":{"type":"string","minLength":1}}}'
      WHEN 'REVIEWER' THEN '{"type":"object","additionalProperties":false,"required":["resultCode","summary","issues"],"properties":{"resultCode":{"enum":["APPROVED","REVISION_REQUIRED"]},"summary":{"type":"string","minLength":1},"issues":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["severity","code","message"],"properties":{"severity":{"type":"string","enum":["CRITICAL","MAJOR","MINOR","INFO"]},"code":{"type":"string","minLength":1},"message":{"type":"string","minLength":1}}}}}}'
      ELSE n.output_schema_json END,
    n.time_updated=NOW(3)
WHERE t.code IN ('requirement-mvp','full-design','full-rd-local')
  AND n.workflow_role IN ('CLARIFIER','ANALYST','AUTHOR','REVIEWER');

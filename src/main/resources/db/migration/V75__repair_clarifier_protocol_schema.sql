-- Keep the CLARIFIER protocol, Runtime output schema and backend validator aligned.
-- V73 previously treated every non-ANALYST/AUTHOR node as a REVIEWER when
-- generating resultCode.enum, which made full-design/REQUIREMENT/clarifier
-- require APPROVED although its protocol and backend require CLARIFIED.
UPDATE stage_agent_node_def n
JOIN workflow_stage_def d ON d.id = n.stage_def_id
JOIN workflow_template_version v ON v.id = d.workflow_version_id
JOIN workflow_template t ON t.id = v.workflow_template_id
SET n.protocol_prompt = 'WORKFLOW PROTOCOL (SYSTEM LOCKED): When blocking information is required, call workflow_request_human_input with one concise question and stop. Otherwise call the built-in finish tool exactly once with ONLY one JSON object containing schemaVersion=1, executionStatus=SUCCESS, resultCode=CLARIFIED, a non-empty summary, artifacts=[], reviewedArtifactRevisionIds=[], issues=[], blockingIssues=[], and metrics={}. Do not use APPROVED, REVISION_REQUIRED, Markdown, or explanatory text.',
    n.output_schema_json = JSON_SET(
      COALESCE(n.output_schema_json, JSON_OBJECT()),
      '$.type', 'object',
      '$.additionalProperties', FALSE,
      '$.required', JSON_ARRAY('schemaVersion','executionStatus','resultCode','summary','artifacts','reviewedArtifactRevisionIds','issues','blockingIssues','metrics'),
      '$.properties.schemaVersion', JSON_OBJECT('const', 1),
      '$.properties.executionStatus', JSON_OBJECT('const', 'SUCCESS'),
      '$.properties.resultCode', JSON_OBJECT('enum', JSON_ARRAY('CLARIFIED')),
      '$.properties.summary', JSON_OBJECT('type', 'string', 'minLength', 1),
      '$.properties.artifacts', JSON_OBJECT('type', 'array', 'maxItems', 0),
      '$.properties.reviewedArtifactRevisionIds', JSON_OBJECT('type', 'array', 'maxItems', 0),
      '$.properties.issues', JSON_OBJECT('type', 'array', 'maxItems', 0),
      '$.properties.blockingIssues', JSON_OBJECT('type', 'array', 'maxItems', 0),
      '$.properties.metrics', JSON_OBJECT('type', 'object')
    ),
    n.time_updated = NOW(3)
WHERE t.code = 'full-design'
  AND v.version_no = 1
  AND d.stage_key = 'REQUIREMENT'
  AND n.node_key = 'clarifier';

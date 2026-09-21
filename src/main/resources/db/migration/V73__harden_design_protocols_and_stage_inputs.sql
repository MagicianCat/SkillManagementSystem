-- Make the locked completion contract unambiguous for every full-design node.
-- User-editable profile prompts are intentionally not changed here.
UPDATE stage_agent_node_def n
JOIN workflow_stage_def d ON d.id=n.stage_def_id
JOIN workflow_template_version v ON v.id=d.workflow_version_id
JOIN workflow_template t ON t.id=v.workflow_template_id
SET n.protocol_prompt = CASE n.workflow_role
  WHEN 'ANALYST' THEN CONCAT(
    'WORKFLOW PROTOCOL (SYSTEM LOCKED): Analyze the required upstream artifact revisions. Do not create or save a stage artifact. ',
    'When blocked, call workflow_request_human_input with one concise question and stop. Otherwise call the built-in finish tool exactly once. ',
    'Its message must contain ONLY one JSON object with schemaVersion=1, executionStatus=SUCCESS, resultCode=ANALYZED, ',
    'a non-empty summary, artifacts=[], reviewedArtifactRevisionIds=[], issues=[], blockingIssues=[], and metrics={}. ',
    'Do not use schemaVersion="1.0", executionStatus=COMPLETED, Markdown, or explanatory text.'
  )
  WHEN 'AUTHOR' THEN CONCAT(
    'WORKFLOW PROTOCOL (SYSTEM LOCKED): Read every required upstream documentId/revisionId and call save_artifact_draft with artifactType ',d.artifact_type,'. ',
    'Use the exact documentId and revisionId returned by the tool. Then call the built-in finish tool exactly once. ',
    'Its message must contain ONLY one JSON object with schemaVersion=1, executionStatus=SUCCESS, resultCode=DOCUMENT_CREATED, ',
    'a non-empty summary, artifacts=[{artifactType:',d.artifact_type,',documentId,revisionId}], reviewedArtifactRevisionIds=[], ',
    'issues=[], blockingIssues=[], and metrics={}. Do not invent IDs, use Markdown, or add explanatory text.'
  )
  WHEN 'REVIEWER' THEN CONCAT(
    'WORKFLOW PROTOCOL (SYSTEM LOCKED): Review exactly the latest ',d.artifact_type,' artifact revision named in the task and include that revision ID in reviewedArtifactRevisionIds. ',
    'Call the built-in finish tool exactly once with ONLY one JSON object containing schemaVersion=1, executionStatus=SUCCESS, ',
    'resultCode=APPROVED or REVISION_REQUIRED, a non-empty summary, artifacts=[], reviewedArtifactRevisionIds=[the exact revision ID], ',
    'issues, blockingIssues, and metrics={}. Do not approve another revision, use Markdown, or add explanatory text.'
  )
  ELSE n.protocol_prompt
END,
n.output_schema_json = JSON_OBJECT(
  'type','object','additionalProperties',FALSE,
  'required',JSON_ARRAY('schemaVersion','executionStatus','resultCode','summary','artifacts','reviewedArtifactRevisionIds','issues','blockingIssues','metrics'),
  'properties',JSON_OBJECT(
    'schemaVersion',JSON_OBJECT('const',1),
    'executionStatus',JSON_OBJECT('const','SUCCESS'),
    'resultCode',JSON_OBJECT('enum',CASE n.workflow_role WHEN 'ANALYST' THEN JSON_ARRAY('ANALYZED') WHEN 'AUTHOR' THEN JSON_ARRAY('DOCUMENT_CREATED') ELSE JSON_ARRAY('APPROVED','REVISION_REQUIRED') END),
    'summary',JSON_OBJECT('type','string','minLength',1),
    'artifacts',JSON_OBJECT('type','array'),
    'reviewedArtifactRevisionIds',JSON_OBJECT('type','array'),
    'issues',JSON_OBJECT('type','array'),
    'blockingIssues',JSON_OBJECT('type','array'),
    'metrics',JSON_OBJECT('type','object')
  )
),
n.time_updated=NOW(3)
WHERE t.code='full-design' AND v.version_no=1;

UPDATE agent_workflow_run
SET error_code=COALESCE(error_code,result_code,'RUNTIME_FAILED'),
    error_message=COALESCE(error_message,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.summary')),'Agent runtime failed'),
    time_updated=NOW(3)
WHERE status='FAILED' AND (error_code IS NULL OR error_message IS NULL);

UPDATE agent_workflow_session s
JOIN agent_workflow_run r ON r.session_id=s.id
SET s.status='FAILED',s.time_updated=NOW(3)
WHERE r.status='FAILED'
  AND r.id=(SELECT MAX(x.id) FROM agent_workflow_run x WHERE x.session_id=s.id)
  AND s.status<>'FAILED';

ALTER TABLE agent_workflow_run
    ADD COLUMN runtime_conversation_id VARCHAR(200) NULL AFTER runtime_run_id;

-- Preserve the conversation currently associated with an in-flight execution.
UPDATE agent_workflow_run ar
JOIN agent_workflow_session s ON s.id = ar.session_id
SET ar.runtime_conversation_id = s.runtime_conversation_id
WHERE ar.runtime_conversation_id IS NULL
  AND ar.status IN ('STARTING', 'RUNNING', 'WAITING_HUMAN', 'PAUSED');

CREATE INDEX idx_agent_workflow_run_retry_target
    ON agent_workflow_run(stage_run_id, status, id);

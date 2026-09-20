-- Empty runtime configuration is valid, but it must be represented as an object
-- at the persistence boundary so the Gateway never receives JSON null.
UPDATE agent_profile_version
SET runtime_config_json = JSON_OBJECT(), time_updated = NOW(3)
WHERE runtime_config_json IS NULL;

ALTER TABLE agent_profile_version
    MODIFY COLUMN runtime_config_json JSON NOT NULL;

-- Repair historical START_AGENT commands that exhausted retries while their
-- aggregate was left in QUEUED/STARTING. The user can then retry explicitly.
UPDATE agent_workflow_run ar
JOIN runtime_command c ON c.aggregate_id = ar.id
SET ar.status = 'FAILED',
    ar.error_code = 'AGENT_START_DISPATCH_FAILED',
    ar.error_message = LEFT(COALESCE(c.last_error, 'Agent start dispatch failed'), 2000),
    ar.completed_at = COALESCE(ar.completed_at, NOW(3)),
    ar.time_updated = NOW(3)
WHERE c.command_type = 'START_AGENT'
  AND c.status = 'FAILED'
  AND ar.status IN ('QUEUED', 'STARTING');

UPDATE stage_run sr
SET sr.status = 'HUMAN_REQUIRED', sr.time_updated = NOW(3)
WHERE sr.status = 'RUNNING'
  AND EXISTS (
      SELECT 1 FROM agent_workflow_run ar
      WHERE ar.stage_run_id = sr.id
        AND ar.status = 'FAILED'
        AND ar.error_code = 'AGENT_START_DISPATCH_FAILED'
        AND NOT EXISTS (
            SELECT 1 FROM agent_workflow_run newer
            WHERE newer.session_id = ar.session_id
              AND newer.id > ar.id
              AND newer.status IN ('QUEUED', 'STARTING', 'RUNNING', 'WAITING_HUMAN', 'PAUSED')
        )
  );

-- Repair workflow rows created before stage-level failure propagation was added.
UPDATE workflow_run w
JOIN stage_run s ON s.workflow_run_id = w.id
SET w.status = 'HUMAN_REQUIRED',
    w.time_updated = NOW(3)
WHERE s.status = 'HUMAN_REQUIRED'
  AND w.status = 'RUNNING';

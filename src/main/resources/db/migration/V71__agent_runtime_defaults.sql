-- Ensure every forked/system Agent has usable runtime parameters.
UPDATE agent_profile_version
SET temperature = COALESCE(temperature, 0.2),
    max_iteration_per_run = COALESCE(max_iteration_per_run, 30),
    timeout_seconds = COALESCE(timeout_seconds, 1800),
    time_updated = NOW(3)
WHERE temperature IS NULL
   OR max_iteration_per_run IS NULL
   OR timeout_seconds IS NULL;

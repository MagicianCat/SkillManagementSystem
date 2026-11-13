-- Reproject existing progress documents so the worker removes its own detail tables.
UPDATE collab_requirement_progress_doc
SET desired_revision = desired_revision + 1,
    time_updated = CURRENT_TIMESTAMP(3)
WHERE block_map_json IS NOT NULL
  AND JSON_LENGTH(JSON_EXTRACT(block_map_json, '$.tables')) > 1;

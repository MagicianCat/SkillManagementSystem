ALTER TABLE command_usage_event
    ADD COLUMN req_id VARCHAR(256) NULL AFTER command_name,
    ADD COLUMN req_id_source VARCHAR(32) NULL AFTER req_id,
    ADD INDEX idx_command_usage_req_time (req_id, invoked_at);

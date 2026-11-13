-- Freeze the imported ASDM toolset category when a command event is first received.
ALTER TABLE command_usage_event
  ADD COLUMN asdm_asset_id BIGINT NULL,
  ADD COLUMN asdm_mapping_id BIGINT NULL,
  ADD COLUMN asdm_first_category_code VARCHAR(64) NULL,
  ADD COLUMN asdm_development_stage VARCHAR(32) NULL,
  ADD COLUMN asdm_match_status VARCHAR(32) NOT NULL DEFAULT 'NO_ASSET',
  ADD KEY idx_command_asdm_asset_time (asdm_asset_id, invoked_at),
  ADD KEY idx_command_asdm_stage_time (asdm_development_stage, invoked_at);

ALTER TABLE dwd_command_usage_detail
  ADD COLUMN asdm_asset_id BIGINT NULL,
  ADD COLUMN asdm_mapping_id BIGINT NULL,
  ADD COLUMN asdm_first_category_code VARCHAR(64) NULL,
  ADD COLUMN asdm_development_stage VARCHAR(32) NULL,
  ADD COLUMN asdm_match_status VARCHAR(32) NOT NULL DEFAULT 'NO_ASSET',
  ADD COLUMN asdm_resource_id BIGINT NULL,
  ADD COLUMN asdm_resource_version_id BIGINT NULL,
  ADD COLUMN asdm_source_system VARCHAR(128) NULL,
  ADD COLUMN asdm_external_resource_id VARCHAR(255) NULL,
  ADD KEY idx_dwd_command_asdm_stage_time (asdm_development_stage, invoked_at),
  ADD KEY idx_dwd_command_asdm_asset_time (asdm_asset_id, invoked_at);

ALTER TABLE external_resource
  ADD COLUMN development_stage VARCHAR(32) NULL,
  ADD COLUMN sub_stage_key VARCHAR(64) NULL;

ALTER TABLE external_resource_version
  ADD COLUMN development_stage VARCHAR(32) NULL,
  ADD COLUMN sub_stage_key VARCHAR(64) NULL;

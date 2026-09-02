ALTER TABLE artifact_build_task ADD COLUMN artifact_object_key varchar(512) NULL;
ALTER TABLE artifact_build_task ADD COLUMN artifact_sha256 char(64) NULL;
ALTER TABLE artifact_build_task ADD COLUMN artifact_size_bytes bigint NULL;
ALTER TABLE artifact_build_task ADD COLUMN artifact_content_type varchar(128) NULL;

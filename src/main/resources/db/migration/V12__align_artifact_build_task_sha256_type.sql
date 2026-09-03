-- Keep the physical column aligned with ArtifactBuildTaskEntity's JPA mapping.
ALTER TABLE artifact_build_task
    MODIFY COLUMN artifact_sha256 varchar(64) NULL;

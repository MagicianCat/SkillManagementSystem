ALTER TABLE code_graph_repository_snapshot
    ADD COLUMN artifact_repository_alias VARCHAR(100) NULL AFTER artifact_sha256;

UPDATE code_graph_repository_snapshot s
JOIN code_graph_bundle b ON b.artifact_key = s.artifact_key
JOIN code_graph_bundle_repository br
  ON br.bundle_id = b.id
 AND br.repository_snapshot_id = s.id
SET s.artifact_repository_alias = br.repository_alias
WHERE s.artifact_repository_alias IS NULL;

CREATE INDEX idx_code_graph_snapshot_artifact_alias
    ON code_graph_repository_snapshot (artifact_repository_alias);

ALTER TABLE wiki_document
    ADD COLUMN parent_id BIGINT NULL,
    ADD COLUMN source_system VARCHAR(128) NULL,
    ADD COLUMN source_resource_id VARCHAR(255) NULL,
    ADD COLUMN source_entry_path VARCHAR(512) NULL,
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD COLUMN active_source_hash BINARY(32) GENERATED ALWAYS AS
        (CASE WHEN status='ACTIVE' AND source_resource_id IS NOT NULL
            THEN UNHEX(SHA2(CONCAT(source_system, '|', COALESCE(team_id, 0), '|', source_resource_id, '|', COALESCE(source_entry_path, '')), 256))
            ELSE NULL END) STORED,
    ADD CONSTRAINT fk_wiki_document_parent FOREIGN KEY (parent_id) REFERENCES wiki_document(id) ON DELETE RESTRICT,
    ADD INDEX idx_wiki_parent_status (parent_id, status),
    ADD INDEX idx_wiki_external_source (source_system, source_resource_id, team_id, status),
    ADD INDEX idx_wiki_deleted_at (status, deleted_at),
    ADD UNIQUE KEY uk_wiki_active_source (active_source_hash);

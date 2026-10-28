ALTER TABLE wiki_document
    MODIFY COLUMN source_system VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    MODIFY COLUMN source_resource_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    MODIFY COLUMN source_entry_path VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;

ALTER TABLE project_document_source
    DROP FOREIGN KEY fk_project_source_revision,
    ADD CONSTRAINT fk_project_source_revision FOREIGN KEY (source_revision_id) REFERENCES project_document_revision(id) ON DELETE SET NULL;

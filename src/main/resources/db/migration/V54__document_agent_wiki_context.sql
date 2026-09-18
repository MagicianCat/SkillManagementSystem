CREATE TABLE document_agent_session_wiki_context (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    time_updated TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    session_id BIGINT NOT NULL,
    wiki_document_id BIGINT NOT NULL,
    added_by BIGINT NOT NULL,
    ordinal_no INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_da_session_wiki_session FOREIGN KEY (session_id) REFERENCES document_agent_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_da_session_wiki_document FOREIGN KEY (wiki_document_id) REFERENCES wiki_document(id),
    CONSTRAINT fk_da_session_wiki_added_by FOREIGN KEY (added_by) REFERENCES iam_user(id),
    CONSTRAINT uk_da_session_wiki UNIQUE (session_id, wiki_document_id)
);

CREATE INDEX idx_da_session_wiki_order ON document_agent_session_wiki_context(session_id, ordinal_no);

ALTER TABLE document_agent_job_context ADD COLUMN wiki_document_id BIGINT NULL;
ALTER TABLE document_agent_job_context ADD CONSTRAINT fk_da_job_context_wiki FOREIGN KEY (wiki_document_id) REFERENCES wiki_document(id);
CREATE INDEX idx_da_job_context_wiki ON document_agent_job_context(job_id, wiki_document_id);
ALTER TABLE document_agent_job_context ADD CONSTRAINT uk_da_job_context_wiki UNIQUE (job_id, context_kind, wiki_document_id);

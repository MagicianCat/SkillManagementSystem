ALTER TABLE agent_profile ADD COLUMN project_id BIGINT NULL;
CREATE INDEX idx_agent_profile_project ON agent_profile(project_id,source_type,status);
ALTER TABLE agent_profile ADD CONSTRAINT fk_agent_profile_project FOREIGN KEY(project_id) REFERENCES virtual_project(id);

CREATE TABLE project_agent_configuration_context (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 configuration_id BIGINT NOT NULL, context_kind VARCHAR(20) NOT NULL,
 wiki_document_id BIGINT NULL, wiki_revision_no INT NULL,
 feishu_doc_id VARCHAR(512) NULL, feishu_doc_type VARCHAR(64) NULL, title VARCHAR(500) NULL,
 sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_project_config_context(configuration_id,context_kind,wiki_document_id,feishu_doc_id,feishu_doc_type),
 CONSTRAINT fk_project_config_context_config FOREIGN KEY(configuration_id) REFERENCES project_agent_configuration(id) ON DELETE CASCADE,
 CONSTRAINT fk_project_config_context_wiki FOREIGN KEY(wiki_document_id) REFERENCES wiki_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_project_config_context_kind ON project_agent_configuration_context(configuration_id,context_kind,sort_order);

CREATE TABLE workflow_run_context_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, workflow_run_id BIGINT NOT NULL,
 context_kind VARCHAR(20) NOT NULL, wiki_document_id BIGINT NULL, wiki_revision_no INT NULL,
 feishu_doc_id VARCHAR(512) NULL, feishu_doc_type VARCHAR(64) NULL, title VARCHAR(500) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_workflow_run_context(workflow_run_id,context_kind,wiki_document_id,feishu_doc_id,feishu_doc_type),
 CONSTRAINT fk_workflow_run_context_run FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

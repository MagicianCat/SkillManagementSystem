ALTER TABLE workflow_run_code_graph_binding
    ADD COLUMN semantic_index_error_code VARCHAR(100) NULL AFTER semantic_index_status;

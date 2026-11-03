CREATE TABLE collab_asdm_stage_hierarchy (
    stage_id BIGINT NOT NULL PRIMARY KEY,
    index_node_token VARCHAR(128) NOT NULL,
    stage_node_token VARCHAR(128) NOT NULL,
    archive_node_token VARCHAR(128) NULL,
    time_updated DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_collab_asdm_stage_hierarchy_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id)
);

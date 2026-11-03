CREATE TABLE collab_asdm_stage_archive (
    stage_id BIGINT NOT NULL PRIMARY KEY,
    wiki_node_token VARCHAR(128) NOT NULL,
    wiki_obj_token VARCHAR(128) NOT NULL,
    time_created DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_collab_asdm_stage_archive_stage FOREIGN KEY (stage_id) REFERENCES collab_stage(id)
);

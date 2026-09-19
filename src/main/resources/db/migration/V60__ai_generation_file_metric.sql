CREATE TABLE ai_generation_file_metric (
    id BIGINT NOT NULL AUTO_INCREMENT,
    generation_id BIGINT NOT NULL,
    file_extension VARCHAR(32) NULL,
    file_category VARCHAR(32) NOT NULL,
    lines_added BIGINT NOT NULL DEFAULT 0,
    lines_deleted BIGINT NOT NULL DEFAULT 0,
    files_created INT NOT NULL DEFAULT 0,
    files_modified INT NOT NULL DEFAULT 0,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_generation_file_generation FOREIGN KEY (generation_id) REFERENCES ai_generation_event(id) ON DELETE CASCADE,
    INDEX idx_generation_file_generation (generation_id),
    INDEX idx_generation_file_category (file_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

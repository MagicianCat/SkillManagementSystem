CREATE TABLE ops_index_state (
 document_id BIGINT NOT NULL PRIMARY KEY,
 status VARCHAR(24) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_retry_at DATETIME(3) NULL,
 lease_until DATETIME(3) NULL,
 last_success_at DATETIME(3) NULL,
 error_code VARCHAR(128) NULL,
 time_created DATETIME(3) NOT NULL,
 time_updated DATETIME(3) NOT NULL,
 KEY idx_ops_index_retry(status,next_retry_at,lease_until),
 KEY idx_ops_index_backlog(status,time_updated),
 CONSTRAINT fk_ops_index_document FOREIGN KEY(document_id) REFERENCES wiki_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

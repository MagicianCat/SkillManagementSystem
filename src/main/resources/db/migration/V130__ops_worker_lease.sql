CREATE TABLE ops_worker_lease (
 worker_key VARCHAR(80) NOT NULL PRIMARY KEY,
 lease_until DATETIME(3) NOT NULL,
 owner_id VARCHAR(96) NULL,
 time_updated DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO ops_worker_lease(worker_key,lease_until,owner_id,time_updated) VALUES('metrics_aggregate','1970-01-01 00:00:00',NULL,now(3));

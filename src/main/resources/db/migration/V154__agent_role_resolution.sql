CREATE TABLE agent_run_role_resolution (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  resolution_key VARCHAR(64) NOT NULL,
  run_key VARCHAR(128) NOT NULL,
  actor_id BIGINT NOT NULL,
  knowledge_scope VARCHAR(32) NOT NULL,
  normalized_query VARCHAR(255) NOT NULL,
  requested_source VARCHAR(128) NULL,
  result_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_agent_role_resolution_key(resolution_key),
  KEY ix_agent_role_resolution_run(run_key, created_at),
  KEY ix_agent_role_resolution_expiry(expires_at)
);

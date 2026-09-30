ALTER TABLE workflow_stage_def
  ADD COLUMN execution_mode VARCHAR(30) NOT NULL DEFAULT 'AGENT';

CREATE TABLE project_git_repository (
  id BIGINT NOT NULL AUTO_INCREMENT,
  time_created DATETIME(3) NOT NULL,
  time_updated DATETIME(3) NOT NULL,
  project_id BIGINT NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  locator_mode VARCHAR(30) NOT NULL,
  repository_path VARCHAR(500) NOT NULL,
  remote_url VARCHAR(2000) NOT NULL,
  normalized_url VARCHAR(500) NOT NULL,
  default_branch VARCHAR(255),
  tracked_branch VARCHAR(255) NOT NULL,
  status VARCHAR(30) NOT NULL,
  last_validated_at DATETIME(3),
  last_validation_error VARCHAR(2000),
  added_by BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_git_repository (project_id, normalized_url, tracked_branch),
  KEY idx_project_git_repository_project (project_id, status),
  CONSTRAINT fk_project_git_repository_project FOREIGN KEY (project_id) REFERENCES virtual_project(id),
  CONSTRAINT fk_project_git_repository_user FOREIGN KEY (added_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_git_repository_stage (
  id BIGINT NOT NULL AUTO_INCREMENT,
  time_created DATETIME(3) NOT NULL,
  repository_id BIGINT NOT NULL,
  stage_key VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_git_repository_stage (repository_id, stage_key),
  CONSTRAINT fk_project_git_repository_stage_repository FOREIGN KEY (repository_id) REFERENCES project_git_repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_run_git_repository (
  id BIGINT NOT NULL AUTO_INCREMENT,
  time_created DATETIME(3) NOT NULL,
  workflow_run_id BIGINT NOT NULL,
  project_git_repository_id BIGINT NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  normalized_url VARCHAR(500) NOT NULL,
  repository_path VARCHAR(500) NOT NULL,
  tracked_branch VARCHAR(255) NOT NULL,
  added_by BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_run_git_repository (workflow_run_id, normalized_url, tracked_branch),
  KEY idx_workflow_run_git_repository_run (workflow_run_id, status),
  CONSTRAINT fk_workflow_run_git_repository_run FOREIGN KEY (workflow_run_id) REFERENCES workflow_run(id),
  CONSTRAINT fk_workflow_run_git_repository_project_repo FOREIGN KEY (project_git_repository_id) REFERENCES project_git_repository(id),
  CONSTRAINT fk_workflow_run_git_repository_user FOREIGN KEY (added_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_run_git_repository_stage (
  id BIGINT NOT NULL AUTO_INCREMENT,
  workflow_run_git_repository_id BIGINT NOT NULL,
  stage_key VARCHAR(100) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_workflow_run_git_repository_stage (workflow_run_git_repository_id, stage_key),
  CONSTRAINT fk_workflow_run_git_repository_stage_repository FOREIGN KEY (workflow_run_git_repository_id) REFERENCES workflow_run_git_repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stage_git_watch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  time_created DATETIME(3) NOT NULL,
  time_updated DATETIME(3) NOT NULL,
  stage_run_id BIGINT NOT NULL,
  workflow_run_git_repository_id BIGINT NOT NULL,
  baseline_commit_sha VARCHAR(64),
  latest_commit_sha VARCHAR(64),
  status VARCHAR(30) NOT NULL,
  last_polled_at DATETIME(3),
  next_poll_at DATETIME(3),
  consecutive_failures INT NOT NULL DEFAULT 0,
  last_error VARCHAR(2000),
  lease_owner VARCHAR(100),
  lease_until DATETIME(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_stage_git_watch (stage_run_id, workflow_run_git_repository_id),
  KEY idx_stage_git_watch_due (status, next_poll_at, lease_until),
  CONSTRAINT fk_stage_git_watch_stage FOREIGN KEY (stage_run_id) REFERENCES stage_run(id),
  CONSTRAINT fk_stage_git_watch_repository FOREIGN KEY (workflow_run_git_repository_id) REFERENCES workflow_run_git_repository(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE git_commit_observation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  workflow_run_git_repository_id BIGINT NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  parent_sha VARCHAR(64),
  author_name VARCHAR(255),
  author_email VARCHAR(320),
  commit_time DATETIME(3),
  subject VARCHAR(1000),
  observation_type VARCHAR(30) NOT NULL,
  observed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_git_commit_observation (workflow_run_git_repository_id, commit_sha),
  CONSTRAINT fk_git_commit_observation_repository FOREIGN KEY (workflow_run_git_repository_id) REFERENCES workflow_run_git_repository(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

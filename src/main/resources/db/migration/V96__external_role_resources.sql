CREATE TABLE external_role (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_system VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_role_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  role_name VARCHAR(512) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_external_role_source (source_system, external_role_id)
);

CREATE TABLE external_resource (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_system VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_resource_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  resource_type VARCHAR(16) NOT NULL,
  name VARCHAR(512) NOT NULL,
  description TEXT NULL,
  current_version_id BIGINT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_external_resource_source (source_system, external_resource_id)
);

CREATE TABLE external_resource_version (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  resource_id BIGINT NOT NULL,
  external_version VARCHAR(128) NULL,
  file_name VARCHAR(512) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  storage_status VARCHAR(16) NOT NULL,
  received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_external_version_hash (resource_id, sha256),
  UNIQUE KEY uk_external_version_object (object_key),
  KEY ix_external_version_label (resource_id, external_version),
  CONSTRAINT fk_external_version_resource FOREIGN KEY (resource_id) REFERENCES external_resource(id)
);

ALTER TABLE external_resource ADD CONSTRAINT fk_external_current_version
  FOREIGN KEY (current_version_id) REFERENCES external_resource_version(id);

CREATE TABLE external_role_resource (
  role_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (role_id, resource_id),
  CONSTRAINT fk_external_link_role FOREIGN KEY (role_id) REFERENCES external_role(id),
  CONSTRAINT fk_external_link_resource FOREIGN KEY (resource_id) REFERENCES external_resource(id)
);

CREATE TABLE external_import_record (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  resource_version_id BIGINT NOT NULL,
  external_role_id BIGINT NULL,
  entry_path VARCHAR(512) NULL,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NULL,
  draft_version_id BIGINT NULL,
  action VARCHAR(32) NOT NULL,
  result VARCHAR(16) NOT NULL,
  error_code VARCHAR(128) NULL,
  operator_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_external_import_document (resource_version_id, external_role_id, entry_path, target_type),
  KEY ix_external_import_version (resource_version_id),
  KEY ix_external_import_role (external_role_id),
  CONSTRAINT fk_external_import_version FOREIGN KEY (resource_version_id) REFERENCES external_resource_version(id),
  CONSTRAINT fk_external_import_role FOREIGN KEY (external_role_id) REFERENCES external_role(id)
);

CREATE TABLE external_resource_object_lease (
  object_key VARCHAR(512) NOT NULL PRIMARY KEY,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

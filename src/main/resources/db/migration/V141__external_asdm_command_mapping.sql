-- ASDM 命令分类元数据：接收阶段只在版本上保存原始 JSON，管理员导入后才写分类映射表。

ALTER TABLE external_resource_version
  ADD COLUMN asdm_commands_raw_json JSON NULL COMMENT 'ASDM 原始命令分类 JSON 数组，导入前不解析',
  ADD COLUMN asdm_commands_payload_sha256 CHAR(64) NULL COMMENT 'JSON 规范化序列化后的 SHA-256，用于检测待同步';

ALTER TABLE external_asdm_tool_asset
  ADD COLUMN command_mapping_applied_sha256 CHAR(64) NULL COMMENT '已应用到本资产的命令分类 JSON 哈希',
  ADD COLUMN command_mapping_applied_at DATETIME(3) NULL;

CREATE TABLE external_asdm_command_category (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  asset_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  resource_version_id BIGINT NOT NULL,
  source_kind VARCHAR(32) NOT NULL DEFAULT 'EXTERNAL_ASDM_PACKAGE',
  source_system VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_resource_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  tool_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  first_category_code VARCHAR(64) NOT NULL,
  development_stage VARCHAR(32) NOT NULL,
  mapping_payload_sha256 CHAR(64) NOT NULL,
  mapping_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  imported_by BIGINT NOT NULL,
  imported_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_asdm_asset_tool (asset_id, tool_name),
  KEY idx_asdm_tool_stage (development_stage, tool_name),
  KEY idx_asdm_source (source_system, external_resource_id, mapping_status),
  KEY idx_asdm_tool_lookup (tool_name, mapping_status),
  CONSTRAINT fk_asdm_map_asset FOREIGN KEY (asset_id) REFERENCES external_asdm_tool_asset(id),
  CONSTRAINT fk_asdm_map_resource FOREIGN KEY (resource_id) REFERENCES external_resource(id),
  CONSTRAINT fk_asdm_map_version FOREIGN KEY (resource_version_id) REFERENCES external_resource_version(id),
  CONSTRAINT fk_asdm_map_operator FOREIGN KEY (imported_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
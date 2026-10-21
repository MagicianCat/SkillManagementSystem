CREATE TABLE collab_asdm_default_rule_version (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  version VARCHAR(64) NOT NULL,
  rules_json JSON NOT NULL,
  rules_sha256 CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_collab_asdm_default_rule_version(version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE collab_asdm_group_rule_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  group_id BIGINT NOT NULL,
  revision INT NOT NULL,
  base_default_version VARCHAR(64) NOT NULL,
  override_json JSON NOT NULL,
  effective_hash CHAR(64) NOT NULL,
  updated_by BIGINT NOT NULL,
  time_created DATETIME(3) NOT NULL,
  UNIQUE KEY uk_collab_asdm_group_rule_revision(group_id,revision),
  CONSTRAINT fk_collab_asdm_rule_revision_group FOREIGN KEY(group_id) REFERENCES collab_group(id) ON DELETE RESTRICT,
  CONSTRAINT fk_collab_asdm_rule_revision_actor FOREIGN KEY(updated_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
  INDEX idx_collab_asdm_rule_revision_latest(group_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE collab_asdm_requirement_rule_binding (
  requirement_id BIGINT NOT NULL PRIMARY KEY,
  group_revision INT NOT NULL,
  default_version VARCHAR(64) NOT NULL,
  effective_rules_json JSON NOT NULL,
  effective_hash CHAR(64) NOT NULL,
  bound_by BIGINT NULL,
  synced_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_collab_asdm_rule_binding_requirement FOREIGN KEY(requirement_id) REFERENCES collab_project(id) ON DELETE RESTRICT,
  CONSTRAINT fk_collab_asdm_rule_binding_actor FOREIGN KEY(bound_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
  INDEX idx_collab_asdm_rule_binding_hash(effective_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @collab_asdm_default_rules = JSON_OBJECT(
  'REQUIREMENT', JSON_OBJECT('enabled',true,'includeGlobs',JSON_ARRAY(CONCAT('$','{REQ_PATH}/requirement-doc.md'),CONCAT('$','{REQ_PATH}/clarification-notes.md'),CONCAT('$','{REQ_PATH}/change-log.md'),CONCAT('$','{REQ_PATH}/workflow-state.md')),'excludeGlobs',JSON_ARRAY(),'markerGroups',JSON_ARRAY(JSON_ARRAY(CONCAT('$','{REQ_PATH}/requirement-doc.md')))),
  'PRODUCT', JSON_OBJECT('enabled',true,'includeGlobs',JSON_ARRAY(CONCAT('$','{REQ_PATH}/feature-list.md'),CONCAT('$','{REQ_PATH}/system-function-list.md'),CONCAT('$','{REQ_PATH}/system-overview.md'),CONCAT('$','{REQ_PATH}/systems/**/*.md')),'excludeGlobs',JSON_ARRAY(),'markerGroups',JSON_ARRAY(JSON_ARRAY(CONCAT('$','{REQ_PATH}/system-function-list.md')),JSON_ARRAY(CONCAT('$','{REQ_PATH}/systems/SYS-*.md')))),
  'ARCHITECTURE_DESIGN', JSON_OBJECT('enabled',true,'includeGlobs',JSON_ARRAY('workspace/**/system-design.md','workspace/**/impl-context.md','workspace/**/impl-reference.md','workspace/**/db-changes.md','workspace/**/task-list.md','workspace/**/task-list-frontend.md','workspace/**/api-*.md','workspace/**/algorithm-*.md','workspace/**/req-*.md','workspace/**/*-spec.md','workspace/**/database/*.sql'),'excludeGlobs',JSON_ARRAY(),'markerGroups',JSON_ARRAY(JSON_ARRAY('workspace/**/system-design.md'),JSON_ARRAY('workspace/**/task-list.md','workspace/**/task-list-frontend.md'),JSON_ARRAY('workspace/**/taskList/req-*.md'))),
  'UI_DESIGN', JSON_OBJECT('enabled',true,'includeGlobs',JSON_ARRAY(CONCAT('$','{REQ_PATH}/prototypes/**'),CONCAT('$','{REQ_PATH}/prototype/**')),'excludeGlobs',JSON_ARRAY(),'markerGroups',JSON_ARRAY(JSON_ARRAY(CONCAT('$','{REQ_PATH}/prototypes/modification-summary.md')))),
  'FRONTEND_CODING', JSON_OBJECT('enabled',true,'includeGlobs',JSON_ARRAY(CONCAT('$','{REQ_PATH}/**/task-list.md'),'workspace/**/*-review.md'),'excludeGlobs',JSON_ARRAY(),'markerGroups',JSON_ARRAY(JSON_ARRAY('workspace/**/*frontend*/task-list.md','workspace/**/*web*/task-list.md',CONCAT('$','{REQ_PATH}/frontend/**/task-list.md'),CONCAT('$','{REQ_PATH}/web/**/task-list.md')))),
  'BACKEND_CODING', JSON_OBJECT('enabled',true,'includeGlobs',JSON_ARRAY(CONCAT('$','{REQ_PATH}/**/task-list.md'),'workspace/**/*-execution-log.md','workspace/**/*-review.md'),'excludeGlobs',JSON_ARRAY(),'markerGroups',JSON_ARRAY(JSON_ARRAY(CONCAT('$','{REQ_PATH}/task-list.md'),CONCAT('$','{REQ_PATH}/backend/**/task-list.md'),'workspace/**/*backend*/task-list.md'),JSON_ARRAY(CONCAT('$','{REQ_PATH}/backend/**/*-execution-log.md'),'workspace/**/*backend*/*-execution-log.md'))),
  'TESTING', JSON_OBJECT('enabled',true,'includeGlobs',JSON_ARRAY('workspace/testwork/**/*-测试用例.xlsx','workspace/testwork/**/*-测试用例评审报告.md','workspace/testwork/**/req-structure.md','workspace/testwork/**/*-思维脑图.md'),'excludeGlobs',JSON_ARRAY(),'markerGroups',JSON_ARRAY(JSON_ARRAY('workspace/testwork/**/*-测试用例.xlsx'),JSON_ARRAY('workspace/testwork/**/*-测试用例评审报告.md')))
);

INSERT INTO collab_asdm_default_rule_version(version,rules_json,rules_sha256,status,created_at)
VALUES ('v3.0.1', @collab_asdm_default_rules, SHA2(CAST(@collab_asdm_default_rules AS CHAR),256), 'ACTIVE', NOW(3));

INSERT INTO collab_asdm_requirement_rule_binding(requirement_id,group_revision,default_version,effective_rules_json,effective_hash,bound_by,synced_at)
SELECT p.id,0,'v3.0.1',@collab_asdm_default_rules,SHA2(CAST(@collab_asdm_default_rules AS CHAR),256),NULL,NOW(3)
FROM collab_project p
WHERE p.group_id IS NOT NULL;

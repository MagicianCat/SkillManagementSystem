ALTER TABLE agent_profile ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'USER', ADD COLUMN owner_user_id BIGINT NULL, ADD COLUMN is_system_locked BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN derived_from_profile_version_id BIGINT NULL;
UPDATE agent_profile SET source_type='SYSTEM',is_system_locked=TRUE,owner_user_id=NULL WHERE code IN ('requirement-clarifier','requirement-writer','requirement-reviewer');
CREATE INDEX idx_agent_profile_owner ON agent_profile(owner_user_id,source_type,status);
CREATE TABLE agent_team_preset (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 code VARCHAR(100) NOT NULL, name VARCHAR(200) NOT NULL, description VARCHAR(1000), source_type VARCHAR(20) NOT NULL DEFAULT 'SYSTEM', owner_user_id BIGINT NULL, status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', is_default BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(id), UNIQUE KEY uk_agent_team_preset_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agent_team_preset_version (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 preset_id BIGINT NOT NULL, version_no INT NOT NULL, workflow_template_version_id BIGINT NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'DRAFT', changelog VARCHAR(2000), published_by VARCHAR(100), published_at DATETIME(3),
 PRIMARY KEY(id), UNIQUE KEY uk_agent_team_preset_version(preset_id,version_no), CONSTRAINT fk_agent_team_preset_version_preset FOREIGN KEY(preset_id) REFERENCES agent_team_preset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agent_team_preset_node_binding (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 preset_version_id BIGINT NOT NULL, stage_key VARCHAR(100) NOT NULL, node_key VARCHAR(100) NOT NULL, agent_profile_version_id BIGINT NOT NULL, sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_agent_team_preset_node(preset_version_id,stage_key,node_key), CONSTRAINT fk_agent_team_preset_node_version FOREIGN KEY(preset_version_id) REFERENCES agent_team_preset_version(id), CONSTRAINT fk_agent_team_preset_node_profile FOREIGN KEY(agent_profile_version_id) REFERENCES agent_profile_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE project_agent_configuration (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 project_id BIGINT NOT NULL, workflow_template_version_id BIGINT NOT NULL, source_type VARCHAR(30) NOT NULL, source_preset_version_id BIGINT NULL, source_project_id BIGINT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', config_hash CHAR(64), created_by BIGINT NOT NULL, updated_by BIGINT NOT NULL, confirmed_by BIGINT NULL, confirmed_at DATETIME(3), version_no INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_project_agent_config(project_id,version_no), CONSTRAINT fk_project_agent_config_project FOREIGN KEY(project_id) REFERENCES virtual_project(id), CONSTRAINT fk_project_agent_config_created FOREIGN KEY(created_by) REFERENCES iam_user(id), CONSTRAINT fk_project_agent_config_updated FOREIGN KEY(updated_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE project_agent_configuration_node (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 configuration_id BIGINT NOT NULL, stage_key VARCHAR(100) NOT NULL, node_key VARCHAR(100) NOT NULL, agent_profile_version_id BIGINT NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_project_agent_config_node(configuration_id,stage_key,node_key), CONSTRAINT fk_project_agent_config_node_config FOREIGN KEY(configuration_id) REFERENCES project_agent_configuration(id), CONSTRAINT fk_project_agent_config_node_profile FOREIGN KEY(agent_profile_version_id) REFERENCES agent_profile_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE workflow_run ADD COLUMN project_agent_configuration_id BIGINT NULL, ADD COLUMN config_snapshot_json JSON NULL, ADD COLUMN config_hash CHAR(64) NULL;
CREATE TABLE workflow_run_skill_snapshot (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL,
 workflow_run_id BIGINT NOT NULL, agent_profile_version_id BIGINT NOT NULL, skill_id BIGINT NOT NULL, skill_version_id BIGINT NOT NULL,
 required BOOLEAN NOT NULL DEFAULT TRUE, sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_run_profile_skill(workflow_run_id,agent_profile_version_id,skill_id),
 CONSTRAINT fk_run_skill_snapshot_run FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id),
 CONSTRAINT fk_run_skill_snapshot_profile FOREIGN KEY(agent_profile_version_id) REFERENCES agent_profile_version(id),
 CONSTRAINT fk_run_skill_snapshot_skill FOREIGN KEY(skill_id) REFERENCES skill(id),
 CONSTRAINT fk_run_skill_snapshot_version FOREIGN KEY(skill_version_id) REFERENCES skill_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO agent_team_preset(time_created,time_updated,code,name,description,source_type,status,is_default) VALUES(NOW(3),NOW(3),'standard-design-team','Standard Design Team','Default Requirement design agents','SYSTEM','ACTIVE',TRUE) ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO agent_team_preset_version(time_created,time_updated,preset_id,version_no,workflow_template_version_id,status,published_by,published_at)
SELECT NOW(3),NOW(3),p.id,1,v.id,'PUBLISHED','system',NOW(3) FROM agent_team_preset p JOIN workflow_template t ON t.code='requirement-mvp' JOIN workflow_template_version v ON v.workflow_template_id=t.id AND v.version_no=1 WHERE p.code='standard-design-team' ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO agent_team_preset_node_binding(time_created,time_updated,preset_version_id,stage_key,node_key,agent_profile_version_id,sort_order)
SELECT NOW(3),NOW(3),pv.id,'REQUIREMENT',n.node_key,n.agent_profile_version_id,n.sort_order FROM agent_team_preset_version pv JOIN agent_team_preset p ON p.id=pv.preset_id JOIN workflow_template_version wv ON wv.id=pv.workflow_template_version_id JOIN workflow_stage_def d ON d.workflow_version_id=wv.id JOIN stage_agent_node_def n ON n.stage_def_id=d.id WHERE p.code='standard-design-team' AND pv.version_no=1 AND d.stage_key='REQUIREMENT' ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

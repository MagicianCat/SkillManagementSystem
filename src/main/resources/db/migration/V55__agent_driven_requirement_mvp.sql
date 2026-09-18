-- Agent-driven project group MVP. Legacy functional-role tables remain for
-- compatibility; new workflow authorization uses membership_type only.
ALTER TABLE virtual_project_member ADD COLUMN membership_type VARCHAR(16) NOT NULL DEFAULT 'MEMBER';
UPDATE virtual_project_member SET membership_type = CASE WHEN role_key = 'OWNER' THEN 'OWNER' ELSE 'MEMBER' END;
CREATE INDEX idx_project_member_type ON virtual_project_member(project_id, membership_type, status);

CREATE TABLE agent_profile (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 code VARCHAR(100) NOT NULL, name VARCHAR(100) NOT NULL, category VARCHAR(50) NOT NULL,
 description VARCHAR(1000), status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', maintainer VARCHAR(100),
 PRIMARY KEY(id), UNIQUE KEY uk_agent_profile_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agent_profile_version (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 agent_profile_id BIGINT NOT NULL, version_no INT NOT NULL, system_prompt LONGTEXT NOT NULL,
 model_code VARCHAR(100) NOT NULL, temperature DECIMAL(4,3), max_iteration_per_run INT NOT NULL DEFAULT 30,
 timeout_seconds INT NOT NULL DEFAULT 1800, output_schema_json JSON, runtime_config_json JSON,
 status VARCHAR(30) NOT NULL DEFAULT 'DRAFT', changelog VARCHAR(2000), published_by VARCHAR(100), published_at DATETIME(3),
 PRIMARY KEY(id), UNIQUE KEY uk_agent_profile_version(agent_profile_id,version_no),
 CONSTRAINT fk_agent_profile_version_profile FOREIGN KEY(agent_profile_id) REFERENCES agent_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agent_profile_version_skill (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 agent_profile_version_id BIGINT NOT NULL, skill_id BIGINT NOT NULL, version_policy VARCHAR(30) NOT NULL DEFAULT 'LATEST_PUBLISHED', fixed_skill_version_id BIGINT, required BOOLEAN NOT NULL DEFAULT TRUE, sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_agent_profile_version_skill(agent_profile_version_id,skill_id), CONSTRAINT fk_agent_profile_skill_version FOREIGN KEY(agent_profile_version_id) REFERENCES agent_profile_version(id), CONSTRAINT fk_agent_profile_skill_skill FOREIGN KEY(skill_id) REFERENCES skill(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agent_profile_version_tool (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 agent_profile_version_id BIGINT NOT NULL, tool_code VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, permission_mode VARCHAR(30) NOT NULL DEFAULT 'DENY', config_json JSON,
 PRIMARY KEY(id), UNIQUE KEY uk_agent_profile_version_tool(agent_profile_version_id,tool_code), CONSTRAINT fk_agent_profile_tool_version FOREIGN KEY(agent_profile_version_id) REFERENCES agent_profile_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_run (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 project_id BIGINT NOT NULL, workflow_code VARCHAR(100) NOT NULL, workflow_version INT NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'CREATED', started_by BIGINT NOT NULL, started_at DATETIME(3), completed_at DATETIME(3),
 failure_reason VARCHAR(2000), lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(id),
 INDEX idx_workflow_run_project(project_id,status),
 CONSTRAINT fk_workflow_run_project FOREIGN KEY(project_id) REFERENCES virtual_project(id),
 CONSTRAINT fk_workflow_run_user FOREIGN KEY(started_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE workflow_run ADD COLUMN initial_request MEDIUMTEXT NULL, ADD COLUMN context_snapshot_json JSON NULL;
CREATE TABLE stage_run (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 workflow_run_id BIGINT NOT NULL, stage_key VARCHAR(100) NOT NULL, run_no INT NOT NULL DEFAULT 1,
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING', loop_count INT NOT NULL DEFAULT 0, input_snapshot_json JSON,
 output_summary_json JSON, started_at DATETIME(3), completed_at DATETIME(3), stale_reason VARCHAR(2000), lock_version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_stage_run(workflow_run_id,stage_key,run_no), CONSTRAINT fk_stage_run_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_template (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 code VARCHAR(100) NOT NULL, name VARCHAR(200) NOT NULL, description VARCHAR(1000), status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
 PRIMARY KEY(id), UNIQUE KEY uk_workflow_template_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_template_version (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 workflow_template_id BIGINT NOT NULL, version_no INT NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'DRAFT', changelog VARCHAR(2000), published_by VARCHAR(100), published_at DATETIME(3),
 PRIMARY KEY(id), UNIQUE KEY uk_workflow_template_version(workflow_template_id,version_no), CONSTRAINT fk_workflow_template_version_template FOREIGN KEY(workflow_template_id) REFERENCES workflow_template(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_stage_def (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 workflow_version_id BIGINT NOT NULL, stage_key VARCHAR(100) NOT NULL, name VARCHAR(100) NOT NULL, max_loop_count INT NOT NULL DEFAULT 3, completion_policy_json JSON, sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_workflow_stage_def(workflow_version_id,stage_key), CONSTRAINT fk_workflow_stage_def_version FOREIGN KEY(workflow_version_id) REFERENCES workflow_template_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE stage_agent_node_def (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 stage_def_id BIGINT NOT NULL, node_key VARCHAR(100) NOT NULL, name VARCHAR(100) NOT NULL, agent_profile_version_id BIGINT NOT NULL, node_type VARCHAR(30) NOT NULL, max_retries INT NOT NULL DEFAULT 2, sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_stage_agent_node(stage_def_id,node_key), CONSTRAINT fk_stage_agent_node_stage FOREIGN KEY(stage_def_id) REFERENCES workflow_stage_def(id), CONSTRAINT fk_stage_agent_node_profile FOREIGN KEY(agent_profile_version_id) REFERENCES agent_profile_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE stage_agent_edge_def (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 stage_def_id BIGINT NOT NULL, from_node_id BIGINT NOT NULL, to_node_id BIGINT NULL, edge_type VARCHAR(30) NOT NULL DEFAULT 'NORMAL', condition_json JSON NOT NULL, increments_loop BOOLEAN NOT NULL DEFAULT FALSE, priority INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_stage_agent_edge(stage_def_id,from_node_id,to_node_id,priority), CONSTRAINT fk_stage_agent_edge_stage FOREIGN KEY(stage_def_id) REFERENCES workflow_stage_def(id), CONSTRAINT fk_stage_agent_edge_from FOREIGN KEY(from_node_id) REFERENCES stage_agent_node_def(id), CONSTRAINT fk_stage_agent_edge_to FOREIGN KEY(to_node_id) REFERENCES stage_agent_node_def(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agent_workflow_session (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 stage_run_id BIGINT NOT NULL, node_key VARCHAR(100) NOT NULL, profile_version_id BIGINT NOT NULL, runtime_conversation_id VARCHAR(200), status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
 PRIMARY KEY(id), UNIQUE KEY uk_agent_workflow_session(stage_run_id,node_key), CONSTRAINT fk_agent_workflow_session_stage FOREIGN KEY(stage_run_id) REFERENCES stage_run(id), CONSTRAINT fk_agent_workflow_session_profile FOREIGN KEY(profile_version_id) REFERENCES agent_profile_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agent_workflow_run (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 stage_run_id BIGINT NOT NULL, session_id BIGINT NOT NULL, node_key VARCHAR(100) NOT NULL, profile_version_id BIGINT,
 status VARCHAR(30) NOT NULL DEFAULT 'QUEUED', execution_no INT NOT NULL DEFAULT 1,
 result_code VARCHAR(100), result_json JSON, trigger_type VARCHAR(30) NOT NULL DEFAULT 'WORKFLOW',
 runtime_run_id VARCHAR(200), runtime_event_sequence BIGINT NOT NULL DEFAULT 0,
 started_at DATETIME(3), completed_at DATETIME(3), error_code VARCHAR(100), error_message VARCHAR(2000),
 PRIMARY KEY(id), UNIQUE KEY uk_agent_workflow_execution(stage_run_id,node_key,execution_no),
 CONSTRAINT fk_agent_workflow_stage FOREIGN KEY(stage_run_id) REFERENCES stage_run(id), CONSTRAINT fk_agent_workflow_session FOREIGN KEY(session_id) REFERENCES agent_workflow_session(id), CONSTRAINT fk_agent_workflow_profile FOREIGN KEY(profile_version_id) REFERENCES agent_profile_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE runtime_command (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 command_id VARCHAR(100) NOT NULL, command_type VARCHAR(50) NOT NULL, aggregate_type VARCHAR(50) NOT NULL, aggregate_id BIGINT NOT NULL,
 payload_json JSON NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'PENDING', retry_count INT NOT NULL DEFAULT 0, next_retry_at DATETIME(3), last_error VARCHAR(2000),
 PRIMARY KEY(id), UNIQUE KEY uk_runtime_command_id(command_id), INDEX idx_runtime_command_pending(status,next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE runtime_workspace_binding (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 stage_run_id BIGINT NOT NULL, provider VARCHAR(50) NOT NULL DEFAULT 'OPENHANDS', runtime_id VARCHAR(200), workspace_id VARCHAR(200), status VARCHAR(30) NOT NULL DEFAULT 'CREATING', runtime_url VARCHAR(1000), resource_config_json JSON, last_heartbeat_at DATETIME(3),
 PRIMARY KEY(id), UNIQUE KEY uk_runtime_workspace_stage(stage_run_id), CONSTRAINT fk_runtime_workspace_stage FOREIGN KEY(stage_run_id) REFERENCES stage_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE runtime_event_index (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 provider VARCHAR(50) NOT NULL, event_id VARCHAR(100) NOT NULL, workflow_run_id BIGINT NOT NULL, event_type VARCHAR(80) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_runtime_event_provider(provider,event_id), INDEX idx_runtime_event_workflow(workflow_run_id,time_created),
 CONSTRAINT fk_runtime_event_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE human_intervention (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT, agent_run_id BIGINT, user_id BIGINT NOT NULL,
 intervention_type VARCHAR(30) NOT NULL, content LONGTEXT, status VARCHAR(30) NOT NULL DEFAULT 'CREATED', runtime_event_id VARCHAR(100),
 PRIMARY KEY(id), INDEX idx_intervention_workflow(workflow_run_id,time_created),
 CONSTRAINT fk_intervention_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id), CONSTRAINT fk_intervention_user FOREIGN KEY(user_id) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_artifact_revision (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 workflow_run_id BIGINT NOT NULL, artifact_key VARCHAR(150) NOT NULL, revision_no INT NOT NULL, stage_run_id BIGINT, agent_run_id BIGINT,
 parent_revision_id BIGINT, status VARCHAR(30) NOT NULL DEFAULT 'GENERATED', storage_type VARCHAR(30) NOT NULL DEFAULT 'INLINE', storage_key VARCHAR(1000),
 content_hash VARCHAR(128) NOT NULL, content LONGTEXT, metadata_json JSON,
 PRIMARY KEY(id), UNIQUE KEY uk_workflow_artifact_revision(workflow_run_id,artifact_key,revision_no),
 CONSTRAINT fk_artifact_revision_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_final_acceptance (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 workflow_run_id BIGINT NOT NULL, accepted_by BIGINT NOT NULL, decision VARCHAR(30) NOT NULL, comment VARCHAR(2000),
 PRIMARY KEY(id), UNIQUE KEY uk_final_acceptance_workflow(workflow_run_id), CONSTRAINT fk_acceptance_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id), CONSTRAINT fk_acceptance_user FOREIGN KEY(accepted_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_artifact_binding (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT, agent_run_id BIGINT, artifact_kind VARCHAR(50) NOT NULL, project_document_id BIGINT NOT NULL, project_document_revision_id BIGINT NOT NULL, relation_type VARCHAR(30) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_workflow_artifact_binding(agent_run_id,project_document_revision_id,relation_type), INDEX idx_workflow_artifact_latest(workflow_run_id,artifact_kind,relation_type), CONSTRAINT fk_binding_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE workflow_runtime_event (
 id BIGINT NOT NULL AUTO_INCREMENT, event_id VARCHAR(160) NOT NULL, workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT, agent_session_id BIGINT, agent_run_id BIGINT, event_seq BIGINT NOT NULL DEFAULT 0, event_type VARCHAR(80) NOT NULL, payload_json JSON NOT NULL, time_created DATETIME(3) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_workflow_runtime_event(event_id), INDEX idx_workflow_runtime_event_cursor(workflow_run_id,event_seq), CONSTRAINT fk_runtime_event_workflow FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE runtime_command ADD COLUMN lease_until DATETIME(3) NULL, ADD COLUMN target_runtime_id VARCHAR(200) NULL, ADD COLUMN target_conversation_id VARCHAR(200) NULL, ADD COLUMN target_run_id BIGINT NULL;
ALTER TABLE human_intervention ADD COLUMN runtime_command_id BIGINT NULL, ADD COLUMN agent_session_id BIGINT NULL;

-- Versioned, DB-owned Requirement DAG seed. Profiles are deliberately seeded as
-- published defaults; later edits create new versions and never mutate these rows.
INSERT INTO agent_profile(time_created,time_updated,code,name,category,status) VALUES
 (NOW(3),NOW(3),'requirement-clarifier','Requirement Clarifier','requirement','ACTIVE'),
 (NOW(3),NOW(3),'prd-writer','PRD Writer','requirement','ACTIVE'),
 (NOW(3),NOW(3),'prd-reviewer','PRD Reviewer','requirement','ACTIVE')
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO agent_profile_version(time_created,time_updated,agent_profile_id,version_no,system_prompt,model_code,status,published_by,published_at)
SELECT NOW(3),NOW(3),id,1,CONCAT('You are ',name,'. Return the standard structured envelope.'),'default','PUBLISHED','system',NOW(3)
FROM agent_profile WHERE code IN ('requirement-clarifier','prd-writer','prd-reviewer')
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_template(time_created,time_updated,code,name,status) VALUES(NOW(3),NOW(3),'requirement-mvp','Requirement Clarification MVP','ACTIVE')
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_template_version(time_created,time_updated,workflow_template_id,version_no,status,published_by,published_at)
SELECT NOW(3),NOW(3),id,1,'PUBLISHED','system',NOW(3) FROM workflow_template WHERE code='requirement-mvp'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,max_loop_count,completion_policy_json,sort_order)
SELECT NOW(3),NOW(3),v.id,'REQUIREMENT','Requirement',3,'{"type":"NODE_RESULT","node":"reviewer","resultCode":"APPROVED"}',1
FROM workflow_template_version v JOIN workflow_template t ON t.id=v.workflow_template_id WHERE t.code='requirement-mvp' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_node_def(time_created,time_updated,stage_def_id,node_key,name,agent_profile_version_id,node_type,max_retries,sort_order)
SELECT NOW(3),NOW(3),d.id,CASE p.code WHEN 'requirement-clarifier' THEN 'clarifier' WHEN 'prd-writer' THEN 'writer' ELSE 'reviewer' END, p.name,pv.id,CASE WHEN p.code='prd-reviewer' THEN 'REVIEWER' ELSE 'EXECUTOR' END,2,
 CASE p.code WHEN 'requirement-clarifier' THEN 1 WHEN 'prd-writer' THEN 2 ELSE 3 END
FROM workflow_stage_def d JOIN workflow_template_version v ON v.id=d.workflow_version_id JOIN workflow_template t ON t.id=v.workflow_template_id
JOIN agent_profile p ON p.code IN ('requirement-clarifier','prd-writer','prd-reviewer') JOIN agent_profile_version pv ON pv.agent_profile_id=p.id AND pv.version_no=1
WHERE t.code='requirement-mvp' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,a.id,b.id,'NORMAL','{"type":"ALWAYS"}',FALSE,1 FROM workflow_stage_def d JOIN stage_agent_node_def a ON a.stage_def_id=d.id AND a.node_key='clarifier' JOIN stage_agent_node_def b ON b.stage_def_id=d.id AND b.node_key='writer'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,a.id,b.id,'NORMAL','{"type":"ALWAYS"}',FALSE,1 FROM workflow_stage_def d JOIN stage_agent_node_def a ON a.stage_def_id=d.id AND a.node_key='writer' JOIN stage_agent_node_def b ON b.stage_def_id=d.id AND b.node_key='reviewer'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,a.id,b.id,'LOOP','{"type":"RESULT_CODE_EQUALS","field":"resultCode","operator":"EQ","value":"REVISION_REQUIRED"}',TRUE,1 FROM workflow_stage_def d JOIN stage_agent_node_def a ON a.stage_def_id=d.id AND a.node_key='reviewer' JOIN stage_agent_node_def b ON b.stage_def_id=d.id AND b.node_key='writer'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,a.id,NULL,'NORMAL','{"type":"RESULT_CODE_EQUALS","field":"resultCode","operator":"EQ","value":"APPROVED"}',FALSE,2 FROM workflow_stage_def d JOIN stage_agent_node_def a ON a.stage_def_id=d.id AND a.node_key='reviewer'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

-- Versioned four-stage design workflow. The legacy requirement-mvp remains immutable.
SET @stage_artifact_type_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workflow_stage_def' AND column_name = 'artifact_type');
SET @stage_artifact_type_sql := IF(@stage_artifact_type_exists = 0, 'ALTER TABLE workflow_stage_def ADD COLUMN artifact_type VARCHAR(50) NULL', 'SELECT 1');
PREPARE stage_artifact_type_stmt FROM @stage_artifact_type_sql;
EXECUTE stage_artifact_type_stmt;
DEALLOCATE PREPARE stage_artifact_type_stmt;
SET @stage_inputs_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workflow_stage_def' AND column_name = 'input_stage_keys_json');
SET @stage_inputs_sql := IF(@stage_inputs_exists = 0, 'ALTER TABLE workflow_stage_def ADD COLUMN input_stage_keys_json JSON NULL', 'SELECT 1');
PREPARE stage_inputs_stmt FROM @stage_inputs_sql;
EXECUTE stage_inputs_stmt;
DEALLOCATE PREPARE stage_inputs_stmt;
SET @stage_acceptance_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workflow_stage_def' AND column_name = 'acceptance_required');
SET @stage_acceptance_sql := IF(@stage_acceptance_exists = 0, 'ALTER TABLE workflow_stage_def ADD COLUMN acceptance_required BOOLEAN NOT NULL DEFAULT TRUE', 'SELECT 1');
PREPARE stage_acceptance_stmt FROM @stage_acceptance_sql;
EXECUTE stage_acceptance_stmt;
DEALLOCATE PREPARE stage_acceptance_stmt;
SET @workflow_role_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stage_agent_node_def' AND column_name = 'workflow_role');
SET @workflow_role_sql := IF(@workflow_role_exists = 0, 'ALTER TABLE stage_agent_node_def ADD COLUMN workflow_role VARCHAR(30) NULL', 'SELECT 1');
PREPARE workflow_role_stmt FROM @workflow_role_sql;
EXECUTE workflow_role_stmt;
DEALLOCATE PREPARE workflow_role_stmt;

CREATE TABLE IF NOT EXISTS workflow_stage_acceptance (
 id BIGINT NOT NULL AUTO_INCREMENT, time_created DATETIME(3) NOT NULL, time_updated DATETIME(3) NOT NULL,
 workflow_run_id BIGINT NOT NULL, stage_run_id BIGINT NOT NULL, accepted_by BIGINT NOT NULL,
 decision VARCHAR(30) NOT NULL, comment VARCHAR(2000),
 PRIMARY KEY(id), UNIQUE KEY uk_workflow_stage_acceptance(stage_run_id),
 CONSTRAINT fk_stage_acceptance_run FOREIGN KEY(workflow_run_id) REFERENCES workflow_run(id),
 CONSTRAINT fk_stage_acceptance_stage FOREIGN KEY(stage_run_id) REFERENCES stage_run(id),
 CONSTRAINT fk_stage_acceptance_user FOREIGN KEY(accepted_by) REFERENCES iam_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO agent_profile(time_created,time_updated,code,name,category,status,source_type,is_system_locked)
VALUES
 (NOW(3),NOW(3),'product-analyst','Product Analyst','product','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'product-writer','Product Requirements Writer','product','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'product-reviewer','Product Requirements Reviewer','product','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'architecture-analyst','Architecture Analyst','architecture','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'architecture-writer','Architecture Designer','architecture','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'architecture-reviewer','Architecture Reviewer','architecture','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'ui-analyst','UX Analyst','ui','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'ui-writer','UI Designer','ui','ACTIVE','SYSTEM',TRUE),
 (NOW(3),NOW(3),'ui-reviewer','UI Design Reviewer','ui','ACTIVE','SYSTEM',TRUE)
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated),is_system_locked=TRUE;

INSERT INTO agent_profile_version(time_created,time_updated,agent_profile_id,version_no,system_prompt,model_code,output_schema_json,runtime_config_json,status,published_by,published_at)
SELECT NOW(3),NOW(3),p.id,1,CONCAT('You are ',p.name,'. Produce the stage-specific design artifact and follow the locked workflow protocol.'),'default','{"type":"object"}',JSON_OBJECT(),'PUBLISHED','system',NOW(3)
FROM agent_profile p
WHERE p.code IN ('product-analyst','product-writer','product-reviewer','architecture-analyst','architecture-writer','architecture-reviewer','ui-analyst','ui-writer','ui-reviewer')
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO workflow_template(time_created,time_updated,code,name,description,status)
VALUES(NOW(3),NOW(3),'full-design','Full Product Design Workflow','Requirement, product, architecture and UI design with parallel downstream stages','ACTIVE')
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_template_version(time_created,time_updated,workflow_template_id,version_no,status,published_by,published_at)
SELECT NOW(3),NOW(3),id,1,'PUBLISHED','system',NOW(3) FROM workflow_template WHERE code='full-design'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,display_name_zh,max_loop_count,completion_policy_json,sort_order,artifact_type,input_stage_keys_json,acceptance_required)
SELECT NOW(3),NOW(3),v.id,'REQUIREMENT','Requirement','需求',3,'{"type":"NODE_RESULT","node":"reviewer","resultCode":"APPROVED"}',1,'REQUIREMENT','[]',TRUE
FROM workflow_template_version v JOIN workflow_template t ON t.id=v.workflow_template_id WHERE t.code='full-design' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,display_name_zh,max_loop_count,completion_policy_json,sort_order,artifact_type,input_stage_keys_json,acceptance_required)
SELECT NOW(3),NOW(3),v.id,'PRODUCT','Product','产品',3,'{"type":"NODE_RESULT","node":"reviewer","resultCode":"APPROVED"}',2,'PRD','["REQUIREMENT"]',TRUE
FROM workflow_template_version v JOIN workflow_template t ON t.id=v.workflow_template_id WHERE t.code='full-design' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,display_name_zh,max_loop_count,completion_policy_json,sort_order,artifact_type,input_stage_keys_json,acceptance_required)
SELECT NOW(3),NOW(3),v.id,'ARCHITECTURE_DESIGN','Architecture Design','架构设计',3,'{"type":"NODE_RESULT","node":"reviewer","resultCode":"APPROVED"}',3,'ARCHITECTURE','["REQUIREMENT","PRODUCT"]',TRUE
FROM workflow_template_version v JOIN workflow_template t ON t.id=v.workflow_template_id WHERE t.code='full-design' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,display_name_zh,max_loop_count,completion_policy_json,sort_order,artifact_type,input_stage_keys_json,acceptance_required)
SELECT NOW(3),NOW(3),v.id,'UI_DESIGN','UI Design','UI设计',3,'{"type":"NODE_RESULT","node":"reviewer","resultCode":"APPROVED"}',4,'UI_DESIGN','["REQUIREMENT","PRODUCT"]',TRUE
FROM workflow_template_version v JOIN workflow_template t ON t.id=v.workflow_template_id WHERE t.code='full-design' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

-- Seed the same three-node DAG for every stage. Role and protocol metadata are
-- workflow-owned; users only edit the forked profile instructions and Skills.
INSERT INTO stage_agent_node_def(time_created,time_updated,stage_def_id,node_key,name,display_name_zh,agent_profile_version_id,node_type,workflow_role,max_retries,sort_order,protocol_prompt,output_schema_json)
SELECT NOW(3),NOW(3),d.id,'analyst',p.name,CASE d.stage_key WHEN 'PRODUCT' THEN '产品分析' WHEN 'ARCHITECTURE_DESIGN' THEN '架构分析' ELSE 'UX分析' END,pv.id,'EXECUTOR','ANALYST',2,1,
 'WORKFLOW PROTOCOL (SYSTEM LOCKED): Return exactly one standard JSON envelope with resultCode=ANALYZED, artifacts=[], reviewedArtifactRevisionIds=[], issues=[], blockingIssues=[] and metrics={}. Use workflow_request_human_input for a blocking question.',
 '{"type":"object","additionalProperties":false,"required":["schemaVersion","executionStatus","resultCode","summary","artifacts","reviewedArtifactRevisionIds","issues","blockingIssues","metrics"]}'
FROM workflow_stage_def d JOIN workflow_template_version v ON v.id=d.workflow_version_id JOIN workflow_template t ON t.id=v.workflow_template_id
JOIN agent_profile p ON p.code=CASE d.stage_key WHEN 'PRODUCT' THEN 'product-analyst' WHEN 'ARCHITECTURE_DESIGN' THEN 'architecture-analyst' ELSE 'ui-analyst' END
JOIN agent_profile_version pv ON pv.agent_profile_id=p.id AND pv.version_no=1
WHERE t.code='full-design' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_node_def(time_created,time_updated,stage_def_id,node_key,name,display_name_zh,agent_profile_version_id,node_type,workflow_role,max_retries,sort_order,protocol_prompt,output_schema_json)
SELECT NOW(3),NOW(3),d.id,'writer',p.name,CASE d.stage_key WHEN 'PRODUCT' THEN '产品需求文档' WHEN 'ARCHITECTURE_DESIGN' THEN '架构设计说明书' ELSE 'UI设计说明书' END,pv.id,'EXECUTOR','AUTHOR',2,2,
 CONCAT('WORKFLOW PROTOCOL (SYSTEM LOCKED): Call save_artifact_draft with artifactType ',d.artifact_type,'. Return exactly one standard JSON envelope with resultCode=DOCUMENT_CREATED and the saved documentId/revisionId in artifacts.'),
 '{"type":"object","additionalProperties":false,"required":["schemaVersion","executionStatus","resultCode","summary","artifacts","reviewedArtifactRevisionIds","issues","blockingIssues","metrics"]}'
FROM workflow_stage_def d JOIN workflow_template_version v ON v.id=d.workflow_version_id JOIN workflow_template t ON t.id=v.workflow_template_id
JOIN agent_profile p ON p.code=CASE d.stage_key WHEN 'PRODUCT' THEN 'product-writer' WHEN 'ARCHITECTURE_DESIGN' THEN 'architecture-writer' ELSE 'ui-writer' END
JOIN agent_profile_version pv ON pv.agent_profile_id=p.id AND pv.version_no=1
WHERE t.code='full-design' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_node_def(time_created,time_updated,stage_def_id,node_key,name,display_name_zh,agent_profile_version_id,node_type,workflow_role,max_retries,sort_order,protocol_prompt,output_schema_json)
SELECT NOW(3),NOW(3),d.id,'reviewer',p.name,CASE d.stage_key WHEN 'PRODUCT' THEN '产品评审' WHEN 'ARCHITECTURE_DESIGN' THEN '架构评审' ELSE 'UI设计评审' END,pv.id,'REVIEWER','REVIEWER',2,3,
 'WORKFLOW PROTOCOL (SYSTEM LOCKED): Review exactly the latest stage artifact and include its revision ID in reviewedArtifactRevisionIds. Return APPROVED or REVISION_REQUIRED with concrete issues.',
 '{"type":"object","additionalProperties":false,"required":["schemaVersion","executionStatus","resultCode","summary","artifacts","reviewedArtifactRevisionIds","issues","blockingIssues","metrics"]}'
FROM workflow_stage_def d JOIN workflow_template_version v ON v.id=d.workflow_version_id JOIN workflow_template t ON t.id=v.workflow_template_id
JOIN agent_profile p ON p.code=CASE d.stage_key WHEN 'PRODUCT' THEN 'product-reviewer' WHEN 'ARCHITECTURE_DESIGN' THEN 'architecture-reviewer' ELSE 'ui-reviewer' END
JOIN agent_profile_version pv ON pv.agent_profile_id=p.id AND pv.version_no=1
WHERE t.code='full-design' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,a.id,w.id,'NORMAL','{"type":"ALWAYS"}',FALSE,1 FROM workflow_stage_def d JOIN stage_agent_node_def a ON a.stage_def_id=d.id AND a.node_key='analyst' JOIN stage_agent_node_def w ON w.stage_def_id=d.id AND w.node_key='writer' WHERE d.workflow_version_id=(SELECT id FROM workflow_template_version WHERE version_no=1 AND workflow_template_id=(SELECT id FROM workflow_template WHERE code='full-design')) ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,w.id,r.id,'NORMAL','{"type":"ALWAYS"}',FALSE,1 FROM workflow_stage_def d JOIN stage_agent_node_def w ON w.stage_def_id=d.id AND w.node_key='writer' JOIN stage_agent_node_def r ON r.stage_def_id=d.id AND r.node_key='reviewer' WHERE d.workflow_version_id=(SELECT id FROM workflow_template_version WHERE version_no=1 AND workflow_template_id=(SELECT id FROM workflow_template WHERE code='full-design')) ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,r.id,w.id,'LOOP','{"type":"RESULT_CODE_EQUALS","field":"resultCode","operator":"EQ","value":"REVISION_REQUIRED"}',TRUE,1 FROM workflow_stage_def d JOIN stage_agent_node_def r ON r.stage_def_id=d.id AND r.node_key='reviewer' JOIN stage_agent_node_def w ON w.stage_def_id=d.id AND w.node_key='writer' WHERE d.workflow_version_id=(SELECT id FROM workflow_template_version WHERE version_no=1 AND workflow_template_id=(SELECT id FROM workflow_template WHERE code='full-design')) ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),d.id,r.id,NULL,'NORMAL','{"type":"RESULT_CODE_EQUALS","field":"resultCode","operator":"EQ","value":"APPROVED"}',FALSE,2 FROM workflow_stage_def d JOIN stage_agent_node_def r ON r.stage_def_id=d.id AND r.node_key='reviewer' WHERE d.workflow_version_id=(SELECT id FROM workflow_template_version WHERE version_no=1 AND workflow_template_id=(SELECT id FROM workflow_template WHERE code='full-design')) ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO agent_team_preset(time_created,time_updated,code,name,description,source_type,status,is_default)
VALUES(NOW(3),NOW(3),'full-design-team','Full Design Team','Requirement, product, architecture and UI design agents','SYSTEM','ACTIVE',TRUE)
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated),is_default=TRUE;
INSERT INTO agent_team_preset_version(time_created,time_updated,preset_id,version_no,workflow_template_version_id,status,published_by,published_at)
SELECT NOW(3),NOW(3),p.id,1,v.id,'PUBLISHED','system',NOW(3) FROM agent_team_preset p JOIN workflow_template t ON t.code='full-design' JOIN workflow_template_version v ON v.workflow_template_id=t.id AND v.version_no=1 WHERE p.code='full-design-team'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO agent_team_preset_node_binding(time_created,time_updated,preset_version_id,stage_key,node_key,agent_profile_version_id,sort_order)
SELECT NOW(3),NOW(3),pv.id,d.stage_key,n.node_key,n.agent_profile_version_id,n.sort_order FROM agent_team_preset_version pv JOIN agent_team_preset p ON p.id=pv.preset_id JOIN workflow_template_version wv ON wv.id=pv.workflow_template_version_id JOIN workflow_stage_def d ON d.workflow_version_id=wv.id JOIN stage_agent_node_def n ON n.stage_def_id=d.id WHERE p.code='full-design-team' AND pv.version_no=1 ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

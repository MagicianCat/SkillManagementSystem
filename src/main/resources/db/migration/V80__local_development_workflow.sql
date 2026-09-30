INSERT INTO workflow_template(time_created,time_updated,code,name,description,status)
VALUES(NOW(3),NOW(3),'full-rd-local','Full R&D Local Workflow','Agent design followed by local backend and frontend coding watched through Git','ACTIVE')
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO workflow_template_version(time_created,time_updated,workflow_template_id,version_no,status,published_by,published_at)
SELECT NOW(3),NOW(3),id,1,'PUBLISHED','system',NOW(3) FROM workflow_template WHERE code='full-rd-local'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,display_name_zh,max_loop_count,completion_policy_json,sort_order,artifact_type,input_stage_keys_json,acceptance_required,execution_mode)
SELECT NOW(3),NOW(3),target.id,source.stage_key,source.name,source.display_name_zh,source.max_loop_count,source.completion_policy_json,source.sort_order,source.artifact_type,source.input_stage_keys_json,source.acceptance_required,'AGENT'
FROM workflow_template_version target JOIN workflow_template tt ON tt.id=target.workflow_template_id AND tt.code='full-rd-local' AND target.version_no=1
JOIN workflow_template st ON st.code='full-design' JOIN workflow_template_version sv ON sv.workflow_template_id=st.id AND sv.version_no=1
JOIN workflow_stage_def source ON source.workflow_version_id=sv.id
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,display_name_zh,max_loop_count,completion_policy_json,sort_order,artifact_type,input_stage_keys_json,acceptance_required,execution_mode)
SELECT NOW(3),NOW(3),v.id,'BACKEND_CODING','Backend Coding','后端编码',0,'{"type":"HUMAN_COMPLETE"}',5,NULL,'["ARCHITECTURE_DESIGN","UI_DESIGN"]',FALSE,'GIT_WATCH'
FROM workflow_template_version v JOIN workflow_template t ON t.id=v.workflow_template_id WHERE t.code='full-rd-local' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO workflow_stage_def(time_created,time_updated,workflow_version_id,stage_key,name,display_name_zh,max_loop_count,completion_policy_json,sort_order,artifact_type,input_stage_keys_json,acceptance_required,execution_mode)
SELECT NOW(3),NOW(3),v.id,'FRONTEND_CODING','Frontend Coding','前端编码',0,'{"type":"HUMAN_COMPLETE"}',6,NULL,'["ARCHITECTURE_DESIGN","UI_DESIGN"]',FALSE,'GIT_WATCH'
FROM workflow_template_version v JOIN workflow_template t ON t.id=v.workflow_template_id WHERE t.code='full-rd-local' AND v.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO stage_agent_node_def(time_created,time_updated,stage_def_id,node_key,name,display_name_zh,agent_profile_version_id,node_type,workflow_role,max_retries,sort_order,protocol_prompt,output_schema_json)
SELECT NOW(3),NOW(3),target.id,n.node_key,n.name,n.display_name_zh,n.agent_profile_version_id,n.node_type,n.workflow_role,n.max_retries,n.sort_order,n.protocol_prompt,n.output_schema_json
FROM workflow_stage_def target JOIN workflow_template_version tv ON tv.id=target.workflow_version_id JOIN workflow_template tt ON tt.id=tv.workflow_template_id AND tt.code='full-rd-local'
JOIN workflow_template st ON st.code='full-design' JOIN workflow_template_version sv ON sv.workflow_template_id=st.id AND sv.version_no=1
JOIN workflow_stage_def source ON source.workflow_version_id=sv.id AND source.stage_key=target.stage_key JOIN stage_agent_node_def n ON n.stage_def_id=source.id
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO stage_agent_edge_def(time_created,time_updated,stage_def_id,from_node_id,to_node_id,edge_type,condition_json,increments_loop,priority)
SELECT NOW(3),NOW(3),td.id,tfrom.id,tto.id,e.edge_type,e.condition_json,e.increments_loop,e.priority
FROM workflow_stage_def td JOIN workflow_template_version tv ON tv.id=td.workflow_version_id JOIN workflow_template tt ON tt.id=tv.workflow_template_id AND tt.code='full-rd-local'
JOIN workflow_template st ON st.code='full-design' JOIN workflow_template_version sv ON sv.workflow_template_id=st.id AND sv.version_no=1
JOIN workflow_stage_def sd ON sd.workflow_version_id=sv.id AND sd.stage_key=td.stage_key JOIN stage_agent_edge_def e ON e.stage_def_id=sd.id
JOIN stage_agent_node_def sfrom ON sfrom.id=e.from_node_id JOIN stage_agent_node_def tfrom ON tfrom.stage_def_id=td.id AND tfrom.node_key=sfrom.node_key
LEFT JOIN stage_agent_node_def sto ON sto.id=e.to_node_id LEFT JOIN stage_agent_node_def tto ON tto.stage_def_id=td.id AND tto.node_key=sto.node_key
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

INSERT INTO agent_team_preset(time_created,time_updated,code,name,description,source_type,status,is_default)
VALUES(NOW(3),NOW(3),'full-rd-local-team','Full R&D Local Team','Agent design plus local Git-watched backend and frontend coding','SYSTEM','ACTIVE',FALSE)
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO agent_team_preset_version(time_created,time_updated,preset_id,version_no,workflow_template_version_id,status,published_by,published_at)
SELECT NOW(3),NOW(3),p.id,1,v.id,'PUBLISHED','system',NOW(3) FROM agent_team_preset p JOIN workflow_template t ON t.code='full-rd-local' JOIN workflow_template_version v ON v.workflow_template_id=t.id AND v.version_no=1 WHERE p.code='full-rd-local-team'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);
INSERT INTO agent_team_preset_node_binding(time_created,time_updated,preset_version_id,stage_key,node_key,agent_profile_version_id,sort_order)
SELECT NOW(3),NOW(3),pv.id,d.stage_key,n.node_key,n.agent_profile_version_id,n.sort_order FROM agent_team_preset_version pv JOIN agent_team_preset p ON p.id=pv.preset_id JOIN workflow_template_version wv ON wv.id=pv.workflow_template_version_id JOIN workflow_stage_def d ON d.workflow_version_id=wv.id JOIN stage_agent_node_def n ON n.stage_def_id=d.id WHERE p.code='full-rd-local-team' AND pv.version_no=1
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated);

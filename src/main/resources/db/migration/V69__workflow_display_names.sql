ALTER TABLE workflow_stage_def
    ADD COLUMN display_name_zh VARCHAR(100) NULL AFTER name;

ALTER TABLE stage_agent_node_def
    ADD COLUMN display_name_zh VARCHAR(100) NULL AFTER name;

UPDATE workflow_stage_def
SET display_name_zh = CASE stage_key WHEN 'REQUIREMENT' THEN '需求' ELSE display_name_zh END
WHERE stage_key = 'REQUIREMENT';

UPDATE stage_agent_node_def n
JOIN workflow_stage_def d ON d.id = n.stage_def_id
SET n.display_name_zh = CASE n.node_key
    WHEN 'clarifier' THEN '需求澄清'
    WHEN 'writer' THEN '需求规格说明书'
    WHEN 'reviewer' THEN '需求评审'
    ELSE n.display_name_zh
END
WHERE d.stage_key = 'REQUIREMENT';

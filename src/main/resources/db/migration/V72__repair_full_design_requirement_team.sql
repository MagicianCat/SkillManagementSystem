-- Full Design reuses the proven Requirement team instead of UI profiles.
UPDATE stage_agent_node_def target
JOIN workflow_stage_def target_stage ON target_stage.id = target.stage_def_id
JOIN workflow_template_version target_version ON target_version.id = target_stage.workflow_version_id
JOIN workflow_template target_template ON target_template.id = target_version.workflow_template_id
JOIN workflow_template source_template ON source_template.code = 'requirement-mvp'
JOIN workflow_template_version source_version ON source_version.workflow_template_id = source_template.id AND source_version.version_no = 1
JOIN workflow_stage_def source_stage ON source_stage.workflow_version_id = source_version.id AND source_stage.stage_key = 'REQUIREMENT'
JOIN stage_agent_node_def source ON source.stage_def_id = source_stage.id AND source.node_key = 'clarifier'
SET target.node_key = 'clarifier',
    target.name = source.name,
    target.display_name_zh = source.display_name_zh,
    target.agent_profile_version_id = source.agent_profile_version_id,
    target.workflow_role = 'CLARIFIER',
    target.protocol_prompt = source.protocol_prompt,
    target.output_schema_json = source.output_schema_json,
    target.time_updated = NOW(3)
WHERE target_template.code = 'full-design'
  AND target_version.version_no = 1
  AND target_stage.stage_key = 'REQUIREMENT'
  AND target.node_key = 'analyst';

UPDATE stage_agent_node_def target
JOIN workflow_stage_def target_stage ON target_stage.id = target.stage_def_id
JOIN workflow_template_version target_version ON target_version.id = target_stage.workflow_version_id
JOIN workflow_template target_template ON target_template.id = target_version.workflow_template_id
JOIN workflow_template source_template ON source_template.code = 'requirement-mvp'
JOIN workflow_template_version source_version ON source_version.workflow_template_id = source_template.id AND source_version.version_no = 1
JOIN workflow_stage_def source_stage ON source_stage.workflow_version_id = source_version.id AND source_stage.stage_key = 'REQUIREMENT'
JOIN stage_agent_node_def source ON source.stage_def_id = source_stage.id AND source.node_key = target.node_key
SET target.name = source.name,
    target.display_name_zh = source.display_name_zh,
    target.agent_profile_version_id = source.agent_profile_version_id,
    target.workflow_role = CASE target.node_key WHEN 'writer' THEN 'AUTHOR' ELSE 'REVIEWER' END,
    target.protocol_prompt = source.protocol_prompt,
    target.output_schema_json = source.output_schema_json,
    target.time_updated = NOW(3)
WHERE target_template.code = 'full-design'
  AND target_version.version_no = 1
  AND target_stage.stage_key = 'REQUIREMENT'
  AND target.node_key IN ('writer', 'reviewer');

UPDATE agent_team_preset_node_binding binding
JOIN agent_team_preset_version preset_version ON preset_version.id = binding.preset_version_id
JOIN agent_team_preset preset ON preset.id = preset_version.preset_id
JOIN workflow_template requirement_template ON requirement_template.code = 'requirement-mvp'
JOIN workflow_template_version requirement_version ON requirement_version.workflow_template_id = requirement_template.id AND requirement_version.version_no = 1
JOIN workflow_stage_def requirement_stage ON requirement_stage.workflow_version_id = requirement_version.id AND requirement_stage.stage_key = 'REQUIREMENT'
JOIN stage_agent_node_def requirement_node ON requirement_node.stage_def_id = requirement_stage.id
    AND requirement_node.node_key = CASE binding.node_key WHEN 'analyst' THEN 'clarifier' ELSE binding.node_key END
SET binding.node_key = CASE binding.node_key WHEN 'analyst' THEN 'clarifier' ELSE binding.node_key END,
    binding.agent_profile_version_id = requirement_node.agent_profile_version_id,
    binding.time_updated = NOW(3)
WHERE preset.code = 'full-design-team'
  AND preset_version.version_no = 1
  AND binding.stage_key = 'REQUIREMENT';

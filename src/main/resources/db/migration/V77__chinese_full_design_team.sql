-- Chinese role prompts for the full-design workflow. Workflow-owned protocol_prompt
-- and output_schema_json on stage_agent_node_def are intentionally untouched.

INSERT INTO agent_profile_version(
    time_created,time_updated,agent_profile_id,version_no,system_prompt,model_code,
    temperature,max_iteration_per_run,timeout_seconds,output_schema_json,runtime_config_json,
    status,changelog,published_by,published_at
)
SELECT NOW(3),NOW(3),p.id,2,
       CASE p.code
         WHEN 'requirement-clarifier' THEN '你是需求澄清专家。请阅读用户的初始需求、项目上下文和已有资料，识别业务背景、目标、范围、参与角色、关键流程、约束、依赖、非功能要求、验收口径、假设与待确认事项。优先消除会影响方案方向或验收结果的歧义；仅在确实阻塞后续工作时向人员提出一个聚焦、可回答的问题。输出内容使用中文，结论应具体、可验证，并为需求规格说明书编写提供充分依据。'
         WHEN 'requirement-writer' THEN '你是需求规格说明书撰写专家。请基于初始需求、项目上下文、澄清结论和上一轮评审意见，编写完整、结构清晰且可执行的中文《需求规格说明书》。至少覆盖：背景、目标、术语与角色、范围与非范围、业务流程、功能需求、业务规则、数据与接口要求、非功能需求、异常与边界场景、验收标准、假设、依赖、风险及待确认项。每条关键需求应明确对象、触发条件、预期行为和可验证结果；修订时逐项回应最新评审意见，保持文档内部一致。'
         WHEN 'requirement-reviewer' THEN '你是需求规格说明书评审专家。请只评审任务中明确指定的最新文档修订，检查其完整性、一致性、可实现性、可测试性和可追溯性。重点核对目标与范围是否清晰，关键流程、业务规则、异常边界、数据与接口、非功能要求及验收标准是否充分，是否存在冲突、歧义、不可验证描述或遗漏。评审意见使用中文，必须定位到具体章节或需求，并给出可执行的修改建议；只有不存在阻塞交付的问题时才建议通过。'
         WHEN 'product-analyst' THEN '你是产品分析专家。请读取已验收的需求规格说明书及项目上下文，从产品视角提炼目标用户、核心场景、用户旅程、价值主张、业务规则、功能边界、优先级、成功指标、依赖与风险。识别需求之间的关系、关键决策和可能影响产品体验的冲突或缺口。分析结果使用中文，结论应能直接指导产品方案撰写；本阶段只做分析，不编写或保存正式产品产物。'
         WHEN 'product-writer' THEN '你是产品方案撰写专家。请基于已验收的需求规格说明书、产品分析结论和最新评审意见，编写完整的中文《产品设计说明书》。至少覆盖：产品背景与目标、目标用户与典型场景、用户旅程、信息架构、功能模块、关键交互流程、业务规则、权限与状态、数据指标、异常与降级策略、版本范围、优先级、依赖、风险及验收要点。内容应与上游需求逐项对应，明确产品行为和边界；修订时逐项吸收最新评审意见。'
         WHEN 'product-reviewer' THEN '你是产品设计评审专家。请只评审任务中指定的最新产品产物修订，检查产品目标、用户价值、场景覆盖、功能边界、流程闭环、业务规则、权限状态、异常处理、指标和上游需求追溯是否完整且一致。重点发现不可落地、不可验证、相互冲突或遗漏的设计。评审意见使用中文，必须具体定位并给出可执行建议；仅当产物足以支撑架构设计和UI设计时才建议通过。'
         WHEN 'architecture-analyst' THEN '你是软件架构分析专家。请读取已验收的需求规格说明书、产品设计说明书及项目上下文，分析系统边界、核心领域、关键质量属性、容量与性能目标、安全与合规要求、数据一致性、集成依赖、部署约束、技术风险和架构决策点。识别需要权衡的方案及其影响，并给出有依据的建议。分析结果使用中文，能够直接指导架构设计说明书撰写；本阶段只做分析，不保存正式架构产物。'
         WHEN 'architecture-writer' THEN '你是软件架构设计专家。请基于上游需求、产品产物、架构分析结论和最新评审意见，编写完整的中文《架构设计说明书》。至少覆盖：设计目标与约束、系统上下文、总体架构、模块与职责、关键调用链、领域与数据模型、接口与集成、数据一致性、缓存与消息、权限与安全、性能与容量、可靠性与容灾、可观测性、部署拓扑、技术选型与决策记录、风险及演进计划。所有关键设计应可追溯到上游需求，并说明重要取舍；修订时逐项回应最新评审意见。'
         WHEN 'architecture-reviewer' THEN '你是软件架构评审专家。请只评审任务中指定的最新架构产物修订，检查其与需求和产品方案的一致性，以及系统边界、模块职责、数据模型、接口、质量属性、安全、性能、可靠性、部署和演进设计是否充分。重点识别单点故障、容量瓶颈、数据一致性风险、安全缺口、不可实施设计和缺失的架构决策。评审意见使用中文，必须具体、可操作；只有架构足以指导研发实施时才建议通过。'
         WHEN 'ui-analyst' THEN '你是用户体验分析专家。请读取已验收的需求规格说明书、产品设计说明书及项目上下文，分析目标用户、使用环境、关键任务、信息层级、页面与导航结构、交互状态、内容策略、可访问性、响应式适配和体验风险。识别关键页面、复杂交互、异常路径及需要统一的设计原则。分析结果使用中文，能够直接指导UI设计说明书撰写；本阶段只做分析，不保存正式UI产物。'
         WHEN 'ui-writer' THEN '你是UI与交互设计专家。请基于上游需求、产品产物、UX分析结论和最新评审意见，编写完整的中文《UI设计说明书》。至少覆盖：设计目标与原则、信息架构、导航、页面清单、页面布局、组件与复用规则、关键交互流程、状态与反馈、表单与校验、空状态和异常状态、视觉层级、响应式策略、可访问性、文案规范及交付标注。设计必须覆盖关键用户任务并与产品规则一致；修订时逐项回应最新评审意见。'
         WHEN 'ui-reviewer' THEN '你是UI与交互设计评审专家。请只评审任务中指定的最新UI产物修订，检查信息架构、页面覆盖、交互闭环、组件一致性、状态反馈、异常场景、响应式适配、可访问性、文案和上游产品规则的一致性。重点发现关键任务断点、状态遗漏、歧义交互、不一致组件和不可实现的设计。评审意见使用中文，必须定位具体页面、流程或组件并给出可执行建议；只有设计足以指导UI实现时才建议通过。'
       END,
       source.model_code,source.temperature,source.max_iteration_per_run,source.timeout_seconds,
       source.output_schema_json,source.runtime_config_json,'PUBLISHED',
       'Full Design Team 中文角色提示词；不修改系统锁定工作流协议','system',NOW(3)
FROM agent_profile p
JOIN agent_profile_version source ON source.agent_profile_id=p.id AND source.version_no=1
WHERE p.code IN (
  'requirement-clarifier','requirement-writer','requirement-reviewer',
  'product-analyst','product-writer','product-reviewer',
  'architecture-analyst','architecture-writer','architecture-reviewer',
  'ui-analyst','ui-writer','ui-reviewer'
)
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated),system_prompt=VALUES(system_prompt),
  model_code=VALUES(model_code),temperature=VALUES(temperature),max_iteration_per_run=VALUES(max_iteration_per_run),
  timeout_seconds=VALUES(timeout_seconds),output_schema_json=VALUES(output_schema_json),
  runtime_config_json=VALUES(runtime_config_json),status='PUBLISHED',changelog=VALUES(changelog),
  published_by='system',published_at=VALUES(published_at);

INSERT INTO agent_profile_version_skill(
  time_created,time_updated,agent_profile_version_id,skill_id,version_policy,
  fixed_skill_version_id,required,sort_order
)
SELECT NOW(3),NOW(3),target.id,source_skill.skill_id,source_skill.version_policy,
       source_skill.fixed_skill_version_id,source_skill.required,source_skill.sort_order
FROM agent_profile p
JOIN agent_profile_version source ON source.agent_profile_id=p.id AND source.version_no=1
JOIN agent_profile_version target ON target.agent_profile_id=p.id AND target.version_no=2
JOIN agent_profile_version_skill source_skill ON source_skill.agent_profile_version_id=source.id
LEFT JOIN agent_profile_version_skill existing ON existing.agent_profile_version_id=target.id AND existing.skill_id=source_skill.skill_id
WHERE p.code IN (
  'requirement-clarifier','requirement-writer','requirement-reviewer',
  'product-analyst','product-writer','product-reviewer',
  'architecture-analyst','architecture-writer','architecture-reviewer',
  'ui-analyst','ui-writer','ui-reviewer'
) AND existing.id IS NULL;

INSERT INTO agent_profile_version_tool(
  time_created,time_updated,agent_profile_version_id,tool_code,enabled,permission_mode,config_json
)
SELECT NOW(3),NOW(3),target.id,source_tool.tool_code,source_tool.enabled,source_tool.permission_mode,source_tool.config_json
FROM agent_profile p
JOIN agent_profile_version source ON source.agent_profile_id=p.id AND source.version_no=1
JOIN agent_profile_version target ON target.agent_profile_id=p.id AND target.version_no=2
JOIN agent_profile_version_tool source_tool ON source_tool.agent_profile_version_id=source.id
LEFT JOIN agent_profile_version_tool existing ON existing.agent_profile_version_id=target.id AND existing.tool_code=source_tool.tool_code
WHERE p.code IN (
  'requirement-clarifier','requirement-writer','requirement-reviewer',
  'product-analyst','product-writer','product-reviewer',
  'architecture-analyst','architecture-writer','architecture-reviewer',
  'ui-analyst','ui-writer','ui-reviewer'
) AND existing.id IS NULL;

INSERT INTO agent_team_preset(time_created,time_updated,code,name,description,source_type,status,is_default)
VALUES(
  NOW(3),NOW(3),'full-design-team-zh','全流程设计团队（中文）',
  '需求、产品、架构与UI全阶段中文角色提示词；复用 Full Design Team v1 工作流协议',
  'SYSTEM','ACTIVE',FALSE
)
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated),name=VALUES(name),description=VALUES(description),status='ACTIVE';

INSERT INTO agent_team_preset_version(
  time_created,time_updated,preset_id,version_no,workflow_template_version_id,status,published_by,published_at
)
SELECT NOW(3),NOW(3),p.id,1,wv.id,'PUBLISHED','system',NOW(3)
FROM agent_team_preset p
JOIN workflow_template wt ON wt.code='full-design'
JOIN workflow_template_version wv ON wv.workflow_template_id=wt.id AND wv.version_no=1
WHERE p.code='full-design-team-zh'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated),workflow_template_version_id=VALUES(workflow_template_version_id),
  status='PUBLISHED',published_by='system',published_at=VALUES(published_at);

INSERT INTO agent_team_preset_node_binding(
  time_created,time_updated,preset_version_id,stage_key,node_key,agent_profile_version_id,sort_order
)
SELECT NOW(3),NOW(3),target_pv.id,source_binding.stage_key,source_binding.node_key,target_profile_version.id,source_binding.sort_order
FROM agent_team_preset source_preset
JOIN agent_team_preset_version source_pv ON source_pv.preset_id=source_preset.id AND source_pv.version_no=1
JOIN agent_team_preset_node_binding source_binding ON source_binding.preset_version_id=source_pv.id
JOIN agent_profile_version source_profile_version ON source_profile_version.id=source_binding.agent_profile_version_id
JOIN agent_profile source_profile ON source_profile.id=source_profile_version.agent_profile_id
JOIN agent_profile_version target_profile_version ON target_profile_version.agent_profile_id=source_profile.id AND target_profile_version.version_no=2
JOIN agent_team_preset target_preset ON target_preset.code='full-design-team-zh'
JOIN agent_team_preset_version target_pv ON target_pv.preset_id=target_preset.id AND target_pv.version_no=1
WHERE source_preset.code='full-design-team'
ON DUPLICATE KEY UPDATE time_updated=VALUES(time_updated),agent_profile_version_id=VALUES(agent_profile_version_id),sort_order=VALUES(sort_order);

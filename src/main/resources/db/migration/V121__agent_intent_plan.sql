CREATE TABLE agent_intent_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    intent_code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    decision_type VARCHAR(16) NOT NULL,
    instructions TEXT NOT NULL,
    yes_description TEXT NOT NULL,
    no_description TEXT NOT NULL,
    policy_key VARCHAR(32) NOT NULL,
    min_yes_probability DECIMAL(5,4) NOT NULL DEFAULT 0.7500,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE agent_run_intent_plan (
    run_key VARCHAR(128) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    intents_json JSON NOT NULL,
    constraints_json JSON NOT NULL,
    allowed_artifacts_json JSON NOT NULL,
    allowed_capabilities_json JSON NOT NULL,
    registry_snapshot_json JSON NOT NULL,
    model_key VARCHAR(64) NOT NULL,
    model_request_id VARCHAR(128),
    decision_latency_ms BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

INSERT INTO agent_intent_definition
    (intent_code, display_name, decision_type, instructions, yes_description, no_description, policy_key, sort_order)
VALUES
    ('SKILL_RECOMMEND', 'Skill 推荐', 'INTENT', '用户本轮是否明确要求输出 Skill 推荐、选择或清单？组合请求也应单独判断。', '要求推荐、挑选、列出或组合适用 Skill', '未要求输出 Skill 推荐', 'SKILL', 10),
    ('ROLE_CONTEXT', '角色上下文', 'INTENT', '用户本轮是否要求获取特定角色或岗位的上下文、角色说明或关联 Wiki？泛称 Wiki 不算。', '要求特定角色或岗位的上下文或 Wiki', '没有索要特定角色资料', 'WIKI', 20),
    ('ASDM_TOOL', 'ASDM 工具', 'INTENT', '用户本轮是否要求 ASDM 工具包、下载入口、安装或使用说明？', '要求 ASDM 工具相关资源', '未要求 ASDM 工具相关资源', 'WIKI', 30),
    ('KNOWLEDGE_QA', '知识问答', 'INTENT', '用户本轮是否要求回答公司内部知识、标准、规范或研发设计问题？', '要求解释或回答内部知识问题', '仅索要资源或一般聊天', 'KNOWLEDGE', 40),
    ('GENERAL_CHAT', '一般交流', 'INTENT', '用户本轮是否只是寒暄、感谢、询问助手能力或一般生活话题？', '一般交流或生活问题', '请求企业资源或内部知识', 'CHAT', 50),
    ('EXCLUDE_SKILL_OUTPUT', '排除 Skill 输出', 'CONSTRAINT', '用户本轮是否明确禁止输出 Skill 推荐？没有请求 Skill 不等于禁止。', '明确表示不要 Skill 或不要推荐 Skill', '没有明确禁止 Skill 输出', 'CHAT', 60);

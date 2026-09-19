-- 聚合看板查询常用维度的补充索引
ALTER TABLE ai_generation_event
    ADD INDEX idx_generation_status_time (status, started_at),
    ADD INDEX idx_generation_started (started_at);

ALTER TABLE ai_generation_skill
    ADD INDEX idx_generation_skill_skill (skill_id);

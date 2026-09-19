ALTER TABLE skill_usage_event
    ADD COLUMN ai_generation_id BIGINT NULL AFTER generation_id,
    ADD CONSTRAINT fk_skill_usage_ai_generation FOREIGN KEY (ai_generation_id) REFERENCES ai_generation_event(id) ON DELETE SET NULL,
    ADD INDEX idx_skill_usage_ai_generation (ai_generation_id);

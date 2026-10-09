ALTER TABLE skill_usage_event
    ADD INDEX idx_skill_usage_user_generation (user_id, generation_id);

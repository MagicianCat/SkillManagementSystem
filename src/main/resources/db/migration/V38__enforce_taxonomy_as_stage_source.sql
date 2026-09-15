ALTER TABLE skill MODIFY category_id BIGINT NOT NULL;
ALTER TABLE skill DROP CHECK chk_skill_development_stage;
ALTER TABLE skill DROP COLUMN development_stage;

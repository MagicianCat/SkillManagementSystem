-- Skill 本体增加开发阶段标识。与 skill_version.lifecycle_status 无关。
-- 存量数据统一置为 OTHER（含义：阶段未知/未标记），新数据由应用层默认 REQUIREMENT。
ALTER TABLE skill ADD COLUMN development_stage VARCHAR(32) NOT NULL DEFAULT 'REQUIREMENT' AFTER status;
UPDATE skill SET development_stage = 'OTHER';
ALTER TABLE skill ADD CONSTRAINT chk_skill_development_stage
    CHECK (development_stage IN ('REQUIREMENT','DESIGN','FRONTEND_CODING','BACKEND_CODING','TESTING','RELEASED','OTHER'));

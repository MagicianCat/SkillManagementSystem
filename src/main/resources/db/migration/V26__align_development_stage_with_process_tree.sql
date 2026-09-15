-- DESIGN was previously used for both architecture and UI design.
-- Preserve existing records as the process-tree's architecture branch; new records
-- can explicitly use ARCHITECTURE_DESIGN or UI_DESIGN.
ALTER TABLE skill DROP CHECK chk_skill_development_stage;
ALTER TABLE skill ADD CONSTRAINT chk_skill_development_stage
    CHECK (development_stage IN (
        'REQUIREMENT','ARCHITECTURE_DESIGN','UI_DESIGN',
        'FRONTEND_CODING','BACKEND_CODING','TESTING','RELEASED','OTHER'
    ));

UPDATE skill
SET development_stage = 'ARCHITECTURE_DESIGN'
WHERE development_stage = 'DESIGN';

ALTER TABLE skill_bundle ADD COLUMN include_dependencies TINYINT(1) NOT NULL DEFAULT 0 AFTER os_type;

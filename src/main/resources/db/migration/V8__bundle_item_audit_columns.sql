ALTER TABLE skill_bundle_item ADD COLUMN time_created datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE skill_bundle_item ADD COLUMN time_updated datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

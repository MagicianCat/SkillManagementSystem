ALTER TABLE wiki_platform_review_skill
  ADD COLUMN display_name_snapshot VARCHAR(255) NULL;

UPDATE wiki_platform_review_skill r
JOIN skill s ON s.id = r.skill_id
SET r.display_name_snapshot = s.display_name;

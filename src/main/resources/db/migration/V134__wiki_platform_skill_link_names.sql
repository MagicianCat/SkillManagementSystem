ALTER TABLE wiki_platform_skill_link
  ADD COLUMN approved_display_name VARCHAR(255) NULL;

UPDATE wiki_platform_skill_link l
JOIN skill s ON s.id = l.skill_id
SET l.approved_display_name = s.display_name;

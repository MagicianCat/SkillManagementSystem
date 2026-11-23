ALTER TABLE external_import_job
  ADD COLUMN publication_mode VARCHAR(24) NOT NULL DEFAULT 'REVIEW'
  COMMENT 'SKILL publication mode: REVIEW or DIRECT_PLATFORM';

INSERT INTO iam_permission(permission_key, permission_name, description, time_created, time_updated)
VALUES ('external:resource:skill:publish:direct', '外部 Skill 免审直发',
        '超级管理员可将外部资源 Skill 直接发布到全平台', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),
                        description=VALUES(description), time_updated=NOW(3);

INSERT IGNORE INTO iam_role_permission(role_id, permission_id, created_by, time_created, time_updated)
SELECT r.id, p.id, NULL, NOW(3), NOW(3)
FROM iam_role r JOIN iam_permission p ON p.permission_key='external:resource:skill:publish:direct'
WHERE r.role_key='ADMIN';

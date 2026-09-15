INSERT INTO iam_permission (permission_key, permission_name, description, time_created, time_updated)
VALUES ('admin:telemetry','Skill 使用看板','查询授权范围内的 Skill 使用数据和对话',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), description=VALUES(description), time_updated=CURRENT_TIMESTAMP(6);

INSERT IGNORE INTO iam_role_permission (role_id, permission_id, created_by, time_created, time_updated)
SELECT r.id, p.id, NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM iam_role r CROSS JOIN iam_permission p
WHERE r.role_key='ADMIN' AND p.permission_key='admin:telemetry';

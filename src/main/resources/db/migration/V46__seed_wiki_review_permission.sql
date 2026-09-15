INSERT INTO iam_permission (permission_key, permission_name, description, time_created, time_updated)
VALUES ('wiki:review', '审核 Wiki 平台推广', '审核团队 Wiki 推送到全平台的申请', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), description=VALUES(description), time_updated=CURRENT_TIMESTAMP(6);

INSERT IGNORE INTO iam_role_permission (role_id, permission_id, created_by, time_created, time_updated)
SELECT r.id, p.id, NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM iam_role r CROSS JOIN iam_permission p
WHERE r.role_key IN ('ADMIN', 'REVIEWER') AND p.permission_key='wiki:review';

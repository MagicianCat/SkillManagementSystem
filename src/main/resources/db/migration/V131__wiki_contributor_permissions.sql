INSERT INTO iam_permission (permission_key, permission_name, description, time_created, time_updated)
VALUES ('wiki:create', '创建 Wiki', '创建所属团队 Wiki', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
       ('wiki:edit', '编辑 Wiki', '编辑所属团队 Wiki', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE permission_name = VALUES(permission_name), description = VALUES(description);

INSERT IGNORE INTO iam_role_permission (role_id, permission_id, created_by, time_created, time_updated)
SELECT r.id, p.id, NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM iam_role r JOIN iam_permission p
WHERE (r.role_key = 'CONSUMER' AND p.permission_key IN ('skill:upload', 'skill:edit', 'wiki:create', 'wiki:edit'))
   OR (r.role_key = 'ADMIN' AND p.permission_key IN ('wiki:create', 'wiki:edit'));

INSERT IGNORE INTO iam_user_role (user_id, role_id, created_by, time_created, time_updated)
SELECT u.id, r.id, u.id, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM iam_user u JOIN iam_role r ON r.role_key = 'CONSUMER' AND r.status = 'ACTIVE'
WHERE u.identity_provider = 'FEISHU' AND u.status = 'ACTIVE';
ALTER TABLE wiki_document MODIFY COLUMN document_type VARCHAR(32) NOT NULL DEFAULT 'UNIFIED';


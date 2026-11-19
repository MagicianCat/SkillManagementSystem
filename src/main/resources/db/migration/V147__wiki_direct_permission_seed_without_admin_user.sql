INSERT IGNORE INTO iam_role_permission(role_id, permission_id, created_by, time_created, time_updated)
SELECT r.id, p.id, NULL, NOW(3), NOW(3)
FROM iam_role r JOIN iam_permission p ON p.permission_key='wiki:publish:direct'
WHERE r.role_key IN ('WIKI_DIRECT_PUBLISHER','ADMIN');

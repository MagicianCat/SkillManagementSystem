-- Feishu 同步或早期导入的用户可能没有任何平台角色。
-- 默认按普通用户处理，保证 Skill 市场浏览/下载能力；已有角色的用户不受影响。
INSERT INTO iam_user_role (user_id, role_id, created_by, time_created, time_updated)
SELECT u.id, r.id, u.id, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM iam_user u
JOIN iam_role r ON r.role_key = 'CONSUMER'
WHERE NOT EXISTS (
    SELECT 1 FROM iam_user_role existing WHERE existing.user_id = u.id
);

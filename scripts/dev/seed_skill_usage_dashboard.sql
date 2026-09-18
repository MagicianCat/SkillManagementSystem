-- Local development data for the Skill usage BI dashboard.
-- Idempotent: synthetic identities and events use stable MOCK/mock-bi-* keys.
-- No conversation records or MinIO objects are created.

INSERT IGNORE INTO org_team
    (time_created, time_updated, provider, external_department_id, parent_id, team_name, status, version_no)
VALUES
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-architecture', NULL, '[演示] 研发架构组', 'ACTIVE', 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-backend', NULL, '[演示] 后端研发组', 'ACTIVE', 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-frontend', NULL, '[演示] 前端体验组', 'ACTIVE', 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-quality', NULL, '[演示] 测试效能组', 'ACTIVE', 0);

INSERT IGNORE INTO iam_user
    (time_created, time_updated, identity_provider, external_user_id, username,
     display_name, email, status, last_login_at, version_no)
VALUES
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-01', 'mock_bi_user_01', '[演示] 陈架构', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-02', 'mock_bi_user_02', '[演示] 林架构', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-03', 'mock_bi_user_03', '[演示] 周架构', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-04', 'mock_bi_user_04', '[演示] 王后端', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-05', 'mock_bi_user_05', '[演示] 李后端', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-06', 'mock_bi_user_06', '[演示] 赵后端', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-07', 'mock_bi_user_07', '[演示] 孙前端', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-08', 'mock_bi_user_08', '[演示] 吴前端', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-09', 'mock_bi_user_09', '[演示] 郑前端', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-10', 'mock_bi_user_10', '[演示] 冯测试', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-11', 'mock_bi_user_11', '[演示] 褚测试', NULL, 'ACTIVE', NULL, 0),
    (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'MOCK', 'mock-bi-user-12', 'mock_bi_user_12', '[演示] 卫测试', NULL, 'ACTIVE', NULL, 0);

INSERT IGNORE INTO org_team_member
    (time_created, time_updated, team_id, user_id, membership_type, status, last_synced_at)
SELECT UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), t.id, u.id, 'MEMBER', 'ACTIVE', UTC_TIMESTAMP(3)
FROM iam_user u
JOIN org_team t ON t.provider = 'MOCK'
    AND t.external_department_id = CASE
        WHEN CAST(RIGHT(u.external_user_id, 2) AS UNSIGNED) BETWEEN 1 AND 3 THEN 'mock-bi-architecture'
        WHEN CAST(RIGHT(u.external_user_id, 2) AS UNSIGNED) BETWEEN 4 AND 6 THEN 'mock-bi-backend'
        WHEN CAST(RIGHT(u.external_user_id, 2) AS UNSIGNED) BETWEEN 7 AND 9 THEN 'mock-bi-frontend'
        ELSE 'mock-bi-quality' END
WHERE u.identity_provider = 'MOCK' AND u.external_user_id LIKE 'mock-bi-user-%';

INSERT IGNORE INTO skill_usage_event
    (time_created, time_updated, event_uuid, user_id, skill_id, skill_version_id,
     skill_key_snapshot, installation_id, feishu_user_id_ciphertext, feishu_open_id_ciphertext,
     local_directory, client_session_id, generation_id, client, client_version, agent_type, model,
     invoked_at, received_at, conversation_status, conversation_error_code,
     conversation_chunk_object_key, conversation_attempts)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL SELECT n + 1 FROM seq WHERE n < 720
), recommended_skills AS (
    SELECT s.id skill_id, s.skill_key, s.latest_published_version_id version_id,
           ROW_NUMBER() OVER (ORDER BY ds.sort_order) skill_no
    FROM wiki_document d
    JOIN wiki_document_skill ds ON ds.document_id = d.id
    JOIN skill s ON s.id = ds.skill_id
    WHERE d.title = '研发全流程最佳实践' AND d.status = 'ACTIVE' AND d.platform_visible = 1
), mock_users AS (
    SELECT id user_id, ROW_NUMBER() OVER (ORDER BY external_user_id) user_no
    FROM iam_user
    WHERE identity_provider = 'MOCK' AND external_user_id LIKE 'mock-bi-user-%'
)
SELECT UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), CONCAT('mock-bi-20260917-', LPAD(seq.n, 4, '0')),
       u.user_id, s.skill_id, s.version_id, s.skill_key,
       CONCAT('mock-bi-install-', LPAD(u.user_no, 2, '0')),
       'mock-not-collected', 'mock-not-collected',
       ELT(1 + MOD(seq.n - 1, 8),
           '/mock/projects/customer-center', '/mock/projects/order-platform',
           '/mock/projects/mobile-workbench', '/mock/projects/data-governance',
           '/mock/projects/risk-engine', '/mock/projects/ops-console',
           '/mock/projects/design-system', '/mock/projects/test-platform'),
       CONCAT('mock-bi-session-', LPAD(1 + MOD(seq.n - 1, 96), 3, '0')),
       CONCAT('mock-bi-generation-', LPAD(seq.n, 4, '0')),
       IF(MOD(seq.n, 10) < 8, 'CodeBuddyIDE', 'OpenCode'),
       IF(MOD(seq.n, 10) < 8, '1.0.0-local', '0.9.0-local'),
       IF(MOD(seq.n, 10) < 8, 'codebuddy', 'opencode'),
       ELT(1 + MOD(seq.n - 1, 3), 'custom-local:qwen3.8-max', 'deepseek-v3.2', 'claude-sonnet'),
       DATE_SUB(
           DATE_ADD(DATE_SUB(UTC_TIMESTAMP(3), INTERVAL MOD(seq.n * 7, 30) DAY),
                    INTERVAL (MOD(seq.n * 11, 16) - 1) HOUR),
           INTERVAL HOUR(UTC_TIMESTAMP(3)) HOUR),
       UTC_TIMESTAMP(3), 'NOT_PROVIDED', NULL, NULL, 0
FROM seq
JOIN mock_users u ON u.user_no = 1 + MOD(FLOOR((seq.n - 1) / 36) * 5, 12)
JOIN recommended_skills s ON s.skill_no = 1 + MOD(seq.n * 7, 36);

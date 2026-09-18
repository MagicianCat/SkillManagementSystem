-- Removes only the local BI dashboard sample records created by seed_skill_usage_dashboard.sql.
DELETE FROM skill_usage_event WHERE event_uuid LIKE 'mock-bi-%';
DELETE tm FROM org_team_member tm JOIN iam_user u ON u.id = tm.user_id
WHERE u.identity_provider = 'MOCK' AND u.external_user_id LIKE 'mock-bi-user-%';
DELETE FROM iam_user WHERE identity_provider = 'MOCK' AND external_user_id LIKE 'mock-bi-user-%';
DELETE FROM org_team WHERE provider = 'MOCK' AND external_department_id LIKE 'mock-bi-%';

DELETE rt
FROM auth_refresh_token rt
JOIN iam_user u ON u.id = rt.user_id
WHERE u.identity_provider <> 'FEISHU';

UPDATE iam_user
SET identity_provider = 'LEGACY',
    status = 'LOCKED',
    password_hash = NULL,
    time_updated = CURRENT_TIMESTAMP(6)
WHERE identity_provider <> 'FEISHU';

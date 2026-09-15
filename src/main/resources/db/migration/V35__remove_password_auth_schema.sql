ALTER TABLE iam_user DROP COLUMN password_hash;
ALTER TABLE iam_role_permission MODIFY created_by BIGINT NULL;

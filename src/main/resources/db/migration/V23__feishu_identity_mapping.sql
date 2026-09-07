ALTER TABLE iam_user ADD COLUMN feishu_open_id VARCHAR(128) NULL;
ALTER TABLE iam_user ADD COLUMN feishu_union_id VARCHAR(128) NULL;
ALTER TABLE iam_user ADD COLUMN feishu_user_id VARCHAR(128) NULL;
CREATE UNIQUE INDEX uk_iam_user_feishu_open_id ON iam_user (feishu_open_id);
CREATE UNIQUE INDEX uk_iam_user_feishu_user_id ON iam_user (feishu_user_id);

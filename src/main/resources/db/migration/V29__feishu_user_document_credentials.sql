CREATE TABLE feishu_user_document_credential (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    user_id BIGINT NOT NULL,
    access_token_ciphertext TEXT NOT NULL,
    refresh_token_ciphertext TEXT NOT NULL,
    access_expires_at DATETIME(3) NOT NULL,
    refresh_expires_at DATETIME(3) NULL,
    granted_scopes VARCHAR(2000) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'AUTHORIZED',
    PRIMARY KEY (id),
    CONSTRAINT uk_feishu_user_document_credential_user UNIQUE (user_id),
    CONSTRAINT fk_feishu_user_document_credential_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE CASCADE,
    INDEX idx_feishu_user_document_credential_status (status, access_expires_at)
);

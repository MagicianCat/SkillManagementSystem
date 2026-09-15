CREATE TABLE auth_ide_authorization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    device_code_hash CHAR(64) NOT NULL,
    user_code_hash CHAR(64) NOT NULL,
    pkce_challenge VARCHAR(128) NOT NULL,
    client_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    approved_at DATETIME(3) NULL,
    consumed_at DATETIME(3) NULL,
    user_id BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_ide_authorization_device_hash (device_code_hash),
    UNIQUE KEY uk_auth_ide_authorization_user_hash (user_code_hash),
    KEY idx_auth_ide_authorization_expiry (expires_at),
    CONSTRAINT fk_auth_ide_authorization_user FOREIGN KEY (user_id) REFERENCES iam_user(id)
);

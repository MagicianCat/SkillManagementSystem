CREATE TABLE auth_browser_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    time_created DATETIME(3) NOT NULL,
    time_updated DATETIME(3) NOT NULL,
    user_id BIGINT NOT NULL,
    session_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    client_info VARCHAR(512) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_browser_session_hash (session_hash),
    KEY idx_auth_browser_session_user_expire (user_id, expires_at),
    CONSTRAINT fk_auth_browser_session_user FOREIGN KEY (user_id) REFERENCES iam_user(id) ON DELETE RESTRICT
);

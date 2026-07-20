CREATE TABLE mock_share (
    id CHAR(26) PRIMARY KEY,
    server_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    share_key CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    expires_at TIMESTAMP(3) NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mock_share_server FOREIGN KEY (server_id) REFERENCES mock_server(id) ON DELETE CASCADE,
    CONSTRAINT fk_mock_share_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mock_share_key (share_key),
    KEY idx_mock_share_project_server (project_id, server_id, created_at),
    KEY idx_mock_share_active_key (share_key, revoked_at, expires_at)
) ENGINE=InnoDB;

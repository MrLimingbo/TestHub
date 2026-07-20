CREATE TABLE mock_server (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    public_key CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mock_server_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mock_server_public_key (public_key),
    UNIQUE KEY uk_mock_server_project_name (project_id, name),
    KEY idx_mock_server_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

CREATE TABLE mock_rule (
    id CHAR(26) PRIMARY KEY,
    server_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    http_method VARCHAR(12) NOT NULL,
    path VARCHAR(700) NOT NULL,
    response_status INT NOT NULL,
    response_headers JSON NOT NULL,
    response_body MEDIUMTEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mock_rule_server FOREIGN KEY (server_id) REFERENCES mock_server(id) ON DELETE CASCADE,
    CONSTRAINT fk_mock_rule_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mock_rule_method_path (server_id, http_method, path),
    KEY idx_mock_rule_project_server (project_id, server_id, updated_at)
) ENGINE=InnoDB;

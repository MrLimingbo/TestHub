CREATE TABLE api_debug_history (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    method VARCHAR(12) NOT NULL,
    request_url VARCHAR(2000) NOT NULL,
    response_status INT NULL,
    duration_ms INT NOT NULL,
    response_size INT NOT NULL DEFAULT 0,
    result VARCHAR(24) NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_debug_history_project FOREIGN KEY (project_id) REFERENCES project(id),
    KEY idx_api_debug_history_project_created (project_id, created_at),
    KEY idx_api_debug_history_org_created (organization_id, created_at)
) ENGINE=InnoDB;

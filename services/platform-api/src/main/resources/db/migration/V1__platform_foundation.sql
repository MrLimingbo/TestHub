CREATE TABLE user_account (
    id CHAR(26) PRIMARY KEY,
    email VARCHAR(190) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB;

CREATE TABLE organization (
    id CHAR(26) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_org_code (code)
) ENGINE=InnoDB;

CREATE TABLE project (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    code VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_project_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    UNIQUE KEY uk_project_org_code (organization_id, code)
) ENGINE=InnoDB;

CREATE TABLE membership (
    id CHAR(26) PRIMARY KEY,
    user_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NULL,
    role_code VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_member_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_member_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_membership_scope (user_id, organization_id, project_id, role_code),
    KEY idx_membership_project_user (project_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE personal_access_token (
    id CHAR(26) PRIMARY KEY,
    user_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    token_prefix CHAR(12) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    scopes JSON NOT NULL,
    expires_at TIMESTAMP(3) NULL,
    last_used_at TIMESTAMP(3) NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_token_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_token_hash (token_hash)
) ENGINE=InnoDB;

CREATE TABLE project_parameter (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    environment_code VARCHAR(64) NOT NULL,
    parameter_key VARCHAR(128) NOT NULL,
    parameter_value TEXT NOT NULL,
    secret BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_parameter_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_parameter_scope (project_id, environment_code, parameter_key)
) ENGINE=InnoDB;

CREATE TABLE system_config (
    config_key VARCHAR(128) PRIMARY KEY,
    config_value TEXT NOT NULL,
    secret BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE audit_log (
    id CHAR(26) PRIMARY KEY,
    user_id CHAR(26) NULL,
    organization_id CHAR(26) NULL,
    project_id CHAR(26) NULL,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(128) NULL,
    outcome VARCHAR(24) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    client_ip VARCHAR(64) NULL,
    detail JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_audit_scope_time (organization_id, project_id, created_at),
    KEY idx_audit_user_time (user_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE notification_template (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NULL,
    name VARCHAR(120) NOT NULL,
    event_code VARCHAR(80) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    subject_template VARCHAR(255) NOT NULL,
    body_template TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE execution_task (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    requested_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_task_project_time (project_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE file_object (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_file_object_key (object_key)
) ENGINE=InnoDB;

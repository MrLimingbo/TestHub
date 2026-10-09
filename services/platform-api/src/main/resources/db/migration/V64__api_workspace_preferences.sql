CREATE TABLE api_workspace_preferences (
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    user_id CHAR(26) NOT NULL,
    revision BIGINT NOT NULL,
    state_json MEDIUMTEXT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT chk_api_workspace_preferences_revision CHECK (revision > 0)
);

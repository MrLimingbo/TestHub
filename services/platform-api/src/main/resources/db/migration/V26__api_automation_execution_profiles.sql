CREATE TABLE api_automation_execution_profile (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    environment_code VARCHAR(64) NOT NULL,
    variables JSON NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_profile_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_execution_profile_name (project_id, name),
    KEY idx_api_automation_execution_profile_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

ALTER TABLE api_automation_execution
    ADD COLUMN execution_profile_id CHAR(26) NULL AFTER scenario_version,
    ADD COLUMN execution_profile_version INT NULL AFTER execution_profile_id,
    ADD KEY idx_api_automation_execution_profile (project_id, execution_profile_id, execution_profile_version);

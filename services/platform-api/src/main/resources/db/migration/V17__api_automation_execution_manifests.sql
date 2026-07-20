CREATE TABLE api_automation_execution_manifest (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    scenario_version INT NOT NULL,
    content JSON NOT NULL,
    checksum CHAR(64) NOT NULL,
    step_count INT NOT NULL,
    variable_count INT NOT NULL,
    secret_reference_count INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_manifest_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_manifest_execution (execution_id),
    KEY idx_api_automation_execution_manifest_project_time (project_id, created_at),
    KEY idx_api_automation_execution_manifest_scenario (scenario_id, scenario_version)
) ENGINE=InnoDB;

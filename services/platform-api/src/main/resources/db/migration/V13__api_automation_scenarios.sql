CREATE TABLE api_automation_scenario (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1200) NOT NULL DEFAULT '',
    environment_code VARCHAR(64) NOT NULL DEFAULT 'default',
    failure_strategy VARCHAR(24) NOT NULL DEFAULT 'STOP',
    version INT NOT NULL DEFAULT 1,
    steps JSON NOT NULL,
    variables JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_scenario_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_scenario_name (project_id, name),
    KEY idx_api_automation_scenario_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

CREATE TABLE api_automation_scenario_revision (
    id CHAR(26) PRIMARY KEY,
    scenario_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    version INT NOT NULL,
    snapshot JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_revision_scenario FOREIGN KEY (scenario_id) REFERENCES api_automation_scenario(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_automation_revision_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_revision_version (scenario_id, version),
    KEY idx_api_automation_revision_project_scenario (project_id, scenario_id, version)
) ENGINE=InnoDB;

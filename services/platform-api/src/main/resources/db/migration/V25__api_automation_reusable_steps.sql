CREATE TABLE api_automation_reusable_step (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1200) NOT NULL DEFAULT '',
    version INT NOT NULL DEFAULT 1,
    steps JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_reusable_step_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_reusable_step_name (project_id, name),
    KEY idx_api_automation_reusable_step_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

CREATE TABLE api_automation_reusable_step_revision (
    id CHAR(26) PRIMARY KEY,
    reusable_step_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    version INT NOT NULL,
    snapshot JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_reusable_step_revision_step
        FOREIGN KEY (reusable_step_id) REFERENCES api_automation_reusable_step(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_automation_reusable_step_revision_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_reusable_step_revision_version (reusable_step_id, version),
    KEY idx_api_automation_reusable_step_revision_project (project_id, reusable_step_id, version)
) ENGINE=InnoDB;

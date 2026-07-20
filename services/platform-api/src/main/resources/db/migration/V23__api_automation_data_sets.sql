CREATE TABLE api_automation_data_set (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    source_format VARCHAR(12) NOT NULL,
    columns JSON NOT NULL,
    data_rows JSON NOT NULL,
    row_count INT NOT NULL,
    checksum CHAR(64) NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_data_set_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_automation_data_set_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_api_automation_data_set_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT uk_api_automation_data_set_project_name UNIQUE (project_id, name),
    KEY idx_api_automation_data_set_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

ALTER TABLE api_automation_execution
    ADD COLUMN data_set_id CHAR(26) NULL AFTER scenario_version,
    ADD COLUMN data_set_row_number INT NULL AFTER data_set_id,
    ADD CONSTRAINT fk_api_automation_execution_data_set
        FOREIGN KEY (data_set_id) REFERENCES api_automation_data_set(id) ON DELETE SET NULL,
    ADD KEY idx_api_automation_execution_data_set (data_set_id, data_set_row_number);

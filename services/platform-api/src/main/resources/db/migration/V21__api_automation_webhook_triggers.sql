CREATE TABLE api_automation_webhook_trigger (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    secret_ciphertext VARCHAR(768) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_webhook_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_automation_webhook_scenario FOREIGN KEY (scenario_id) REFERENCES api_automation_scenario(id),
    CONSTRAINT fk_api_automation_webhook_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    UNIQUE KEY uk_api_automation_webhook_project_name (project_id, name),
    KEY idx_api_automation_webhook_project_updated (project_id, updated_at),
    KEY idx_api_automation_webhook_scenario (scenario_id)
) ENGINE=InnoDB;

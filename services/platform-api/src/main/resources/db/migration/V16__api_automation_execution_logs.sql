CREATE TABLE api_automation_execution_log (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    source VARCHAR(24) NOT NULL,
    runner_id VARCHAR(64) NULL,
    level VARCHAR(12) NOT NULL,
    code VARCHAR(80) NOT NULL,
    message VARCHAR(512) NOT NULL,
    stream_record_id VARCHAR(64) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_log_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_log_stream (stream_record_id),
    KEY idx_api_automation_execution_log_project_time (project_id, occurred_at, id),
    KEY idx_api_automation_execution_log_execution_time (execution_id, occurred_at, id)
) ENGINE=InnoDB;

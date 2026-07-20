CREATE TABLE api_automation_execution_http_result (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    request_sequence INT UNSIGNED NOT NULL,
    method VARCHAR(8) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    status_code SMALLINT UNSIGNED NULL,
    duration_millis INT UNSIGNED NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    stream_record_id VARCHAR(64) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_http_result_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_http_result_stream (stream_record_id),
    UNIQUE KEY uk_api_automation_execution_http_result_sequence (execution_id, request_sequence),
    KEY idx_api_automation_execution_http_result_project_time (project_id, occurred_at, id),
    KEY idx_api_automation_execution_http_result_execution_time (execution_id, request_sequence)
) ENGINE=InnoDB;

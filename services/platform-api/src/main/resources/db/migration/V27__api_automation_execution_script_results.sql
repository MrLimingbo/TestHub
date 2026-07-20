CREATE TABLE api_automation_execution_script_result (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    script_phase VARCHAR(24) NOT NULL,
    test_count INT NOT NULL,
    passed_count INT NOT NULL,
    failed_count INT NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    stream_record_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_api_automation_execution_script_stream (stream_record_id),
    KEY idx_api_automation_execution_script_execution (project_id, execution_id, occurred_at),
    CONSTRAINT ck_api_automation_execution_script_phase CHECK (script_phase IN ('PRE_REQUEST', 'POST_RESPONSE')),
    CONSTRAINT ck_api_automation_execution_script_test_count CHECK (test_count BETWEEN 1 AND 100),
    CONSTRAINT ck_api_automation_execution_script_totals CHECK (passed_count >= 0 AND failed_count >= 0 AND passed_count + failed_count = test_count),
    CONSTRAINT fk_api_automation_execution_script_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

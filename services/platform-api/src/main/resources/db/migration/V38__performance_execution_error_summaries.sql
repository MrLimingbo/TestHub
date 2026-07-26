CREATE TABLE performance_test_execution_error (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    thread_group_name VARCHAR(160) NOT NULL,
    response_code VARCHAR(64) NOT NULL,
    error_count BIGINT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_error_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_performance_test_execution_error_summary (execution_id, runner_id, thread_group_name, response_code),
    KEY idx_performance_test_execution_error_report (project_id, execution_id, error_count, occurred_at)
) ENGINE=InnoDB;

CREATE TABLE performance_test_execution_resource_metric (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    cpu_percent DECIMAL(9,4) NOT NULL,
    resident_memory_bytes BIGINT UNSIGNED NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_resource_metric_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_test_execution_resource_metric_series (project_id, execution_id, occurred_at, id)
) ENGINE=InnoDB;

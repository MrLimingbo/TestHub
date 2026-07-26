CREATE TABLE performance_test_execution_metric (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    thread_group_name VARCHAR(160) NOT NULL,
    sample_count BIGINT NOT NULL,
    success_count BIGINT NOT NULL,
    error_count BIGINT NOT NULL,
    active_threads INT NOT NULL,
    throughput_per_second DECIMAL(14,4) NOT NULL,
    average_latency_millis INT NOT NULL,
    p95_latency_millis INT NOT NULL,
    min_latency_millis INT NOT NULL,
    max_latency_millis INT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_metric_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_test_execution_metric_series (project_id, execution_id, occurred_at, id)
) ENGINE=InnoDB;

ALTER TABLE performance_test_execution
    ADD COLUMN routing_attempts INT NOT NULL DEFAULT 0 AFTER runner_id,
    ADD KEY idx_performance_test_execution_runner_active (runner_id, status, updated_at);

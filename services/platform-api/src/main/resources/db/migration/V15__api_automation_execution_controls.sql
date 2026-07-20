ALTER TABLE api_runner_outbox
    ADD COLUMN message_type VARCHAR(24) NOT NULL DEFAULT 'TASK' AFTER execution_id,
    ADD COLUMN max_dispatch_attempts INT NOT NULL DEFAULT 5 AFTER dispatch_attempts,
    ADD KEY idx_api_runner_outbox_execution_type (execution_id, message_type);

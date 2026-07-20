ALTER TABLE api_automation_execution_http_result
    ADD COLUMN attempt_number TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER request_sequence,
    DROP INDEX uk_api_automation_execution_http_result_sequence,
    ADD UNIQUE KEY uk_api_automation_execution_http_result_attempt (execution_id, request_sequence, attempt_number),
    DROP INDEX idx_api_automation_execution_http_result_execution_time,
    ADD KEY idx_api_automation_execution_http_result_execution_time (execution_id, request_sequence, attempt_number);

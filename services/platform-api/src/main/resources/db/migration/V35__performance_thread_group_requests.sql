ALTER TABLE performance_test_thread_group
    ADD COLUMN request_method VARCHAR(10) NOT NULL DEFAULT 'GET' AFTER target_url,
    ADD COLUMN request_headers MEDIUMTEXT NOT NULL DEFAULT ('{}') AFTER request_method,
    ADD COLUMN request_body MEDIUMTEXT NULL AFTER request_headers,
    ADD COLUMN expected_status_code SMALLINT UNSIGNED NULL AFTER request_body,
    ADD COLUMN response_contains VARCHAR(1000) NULL AFTER expected_status_code;

UPDATE performance_test_thread_group
SET request_headers = '{}'
WHERE request_headers = '';

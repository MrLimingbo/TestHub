ALTER TABLE mock_rule
    ADD COLUMN priority INT NOT NULL DEFAULT 0 AFTER path,
    ADD COLUMN request_query JSON NULL AFTER priority,
    ADD COLUMN request_headers JSON NULL AFTER request_query,
    ADD COLUMN request_body_match VARCHAR(16) NOT NULL DEFAULT 'ANY' AFTER request_headers,
    ADD COLUMN request_body MEDIUMTEXT NULL AFTER request_body_match;

UPDATE mock_rule
SET request_query = JSON_OBJECT(), request_headers = JSON_OBJECT()
WHERE request_query IS NULL OR request_headers IS NULL;

ALTER TABLE mock_rule
    MODIFY COLUMN request_query JSON NOT NULL,
    MODIFY COLUMN request_headers JSON NOT NULL,
    DROP INDEX uk_mock_rule_method_path,
    ADD KEY idx_mock_rule_runtime (server_id, http_method, path, enabled, priority);

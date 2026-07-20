ALTER TABLE mock_server
    ADD COLUMN smart_example_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;

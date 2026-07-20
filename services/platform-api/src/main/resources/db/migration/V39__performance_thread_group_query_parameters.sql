ALTER TABLE performance_test_thread_group
    ADD COLUMN query_parameters MEDIUMTEXT NOT NULL DEFAULT ('{}') AFTER request_headers;

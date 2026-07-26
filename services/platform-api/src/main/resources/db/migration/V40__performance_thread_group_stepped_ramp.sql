ALTER TABLE performance_test_thread_group
    ADD COLUMN step_count SMALLINT UNSIGNED NULL AFTER ramp_up_seconds,
    ADD COLUMN step_interval_seconds INT UNSIGNED NULL AFTER step_count;

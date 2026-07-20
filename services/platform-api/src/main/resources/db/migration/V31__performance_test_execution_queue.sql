CREATE TABLE performance_test_execution (
    id CHAR(26) PRIMARY KEY,
    task_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_id CHAR(26) NOT NULL,
    plan_name VARCHAR(160) NOT NULL,
    plan_version INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    status_code VARCHAR(80) NOT NULL DEFAULT 'QUEUED',
    status_message VARCHAR(512) NOT NULL DEFAULT '任务已进入安全投递队列。',
    manifest_ref VARCHAR(256) NOT NULL,
    runner_id VARCHAR(64) NULL,
    requested_by CHAR(26) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    deadline_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_performance_test_execution_task (task_id),
    KEY idx_performance_test_execution_project_created (project_id, created_at),
    KEY idx_performance_test_execution_active_deadline (status, deadline_at)
) ENGINE=InnoDB;

CREATE TABLE performance_test_execution_manifest (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_id CHAR(26) NOT NULL,
    plan_version INT NOT NULL,
    content JSON NOT NULL,
    checksum CHAR(64) NOT NULL,
    thread_group_count INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_manifest_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_performance_test_execution_manifest_execution (execution_id),
    KEY idx_performance_test_execution_manifest_project (project_id, execution_id)
) ENGINE=InnoDB;

CREATE TABLE performance_test_execution_log (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    source VARCHAR(24) NOT NULL,
    runner_id VARCHAR(64) NULL,
    level VARCHAR(16) NOT NULL,
    code VARCHAR(80) NOT NULL,
    message VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_log_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_test_execution_log_execution (project_id, execution_id, occurred_at, id)
) ENGINE=InnoDB;

CREATE TABLE performance_runner_outbox (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    message_type VARCHAR(24) NOT NULL,
    stream_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    dispatch_attempts INT NOT NULL DEFAULT 0,
    max_dispatch_attempts INT NOT NULL DEFAULT 5,
    available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at TIMESTAMP(3) NULL,
    sent_at TIMESTAMP(3) NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_runner_outbox_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_runner_outbox_dispatch (status, available_at, created_at),
    KEY idx_performance_runner_outbox_execution_type (execution_id, message_type)
) ENGINE=InnoDB;

CREATE TABLE performance_test_plan (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_plan_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_performance_test_plan_name (project_id, name),
    KEY idx_performance_test_plan_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

CREATE TABLE performance_test_thread_group (
    id CHAR(26) PRIMARY KEY,
    plan_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    load_model VARCHAR(24) NOT NULL,
    thread_count INT NOT NULL,
    ramp_up_seconds INT NOT NULL,
    duration_seconds INT NOT NULL,
    target_tps INT NULL,
    p95_threshold_millis INT NULL,
    error_threshold_percent DECIMAL(5,2) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_thread_group_plan FOREIGN KEY (plan_id) REFERENCES performance_test_plan(id) ON DELETE CASCADE,
    CONSTRAINT fk_performance_test_thread_group_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_performance_test_thread_group_name (plan_id, name),
    KEY idx_performance_test_thread_group_project_plan (project_id, plan_id, sort_order)
) ENGINE=InnoDB;

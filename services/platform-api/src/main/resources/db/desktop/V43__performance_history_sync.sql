CREATE TABLE performance_history_archive (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    source_project_id CHAR(26) NOT NULL,
    source_execution_id CHAR(26) NOT NULL,
    source_revision CHAR(64) NOT NULL,
    report_sha256 CHAR(64) NOT NULL,
    report_json MEDIUMTEXT NOT NULL,
    plan_name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    imported_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_perf_history_archive_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_perf_history_archive_user FOREIGN KEY (imported_by) REFERENCES user_account(id),
    UNIQUE KEY uk_perf_history_origin (project_id,source_project_id,source_execution_id)
);

CREATE TABLE performance_history_sync_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_json MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_perf_history_sync_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_perf_history_sync_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_perf_history_sync_request (project_id,requested_by,request_key)
);

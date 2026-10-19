-- Revision changes even when a repeated Runner step retains the same timestamp.
ALTER TABLE ui_automation_execution_step_result ADD COLUMN sync_revision BIGINT NOT NULL DEFAULT 1;

CREATE TABLE ui_history_archive (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    source_project_id CHAR(26) NOT NULL,
    source_execution_id CHAR(26) NOT NULL,
    source_revision CHAR(64) NOT NULL,
    report_sha256 CHAR(64) NOT NULL,
    report_json MEDIUMTEXT NOT NULL,
    scenario_name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    imported_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_history_archive_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_history_archive_user FOREIGN KEY (imported_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_history_origin (project_id,source_project_id,source_execution_id)
);

CREATE TABLE ui_history_sync_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_json MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_history_sync_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_history_sync_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_history_sync_request (project_id,requested_by,request_key)
);

CREATE TABLE ui_artifact_archive (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    source_project_id CHAR(26) NOT NULL,
    source_execution_id CHAR(26) NOT NULL,
    source_artifact_id CHAR(26) NOT NULL,
    artifact_type VARCHAR(24) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum CHAR(64) NOT NULL,
    source_archived_at TIMESTAMP(3) NOT NULL,
    bucket VARCHAR(64) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    imported_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_artifact_archive_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_artifact_archive_user FOREIGN KEY (imported_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_artifact_origin (project_id, source_project_id, source_execution_id, source_artifact_id)
) ENGINE=InnoDB;
CREATE TABLE ui_artifact_sync_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    artifact_id CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_artifact_receipt_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_artifact_receipt_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_artifact_request (project_id, requested_by, request_key)
) ENGINE=InnoDB;
-- Independent durable intents deliberately have no FK to the project row locked by the enclosing import.
CREATE TABLE ui_artifact_transfer_intent (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    bucket VARCHAR(64) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
CREATE INDEX idx_ui_artifact_intent_created ON ui_artifact_transfer_intent(created_at);

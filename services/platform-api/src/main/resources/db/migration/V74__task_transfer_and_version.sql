ALTER TABLE execution_task ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE execution_task ADD COLUMN source_task_id CHAR(26) NULL;
ALTER TABLE execution_task ADD COLUMN source_status VARCHAR(32) NULL;
ALTER TABLE execution_task ADD COLUMN source_version BIGINT NULL;
ALTER TABLE execution_task ADD COLUMN source_created_at DATETIME(3) NULL;
ALTER TABLE execution_task ADD COLUMN source_updated_at DATETIME(3) NULL;

CREATE TABLE task_transfer_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_task_transfer_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_task_transfer_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_task_transfer_request (project_id, requested_by, request_key)
);

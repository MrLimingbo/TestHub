ALTER TABLE file_object ADD COLUMN sync_key CHAR(26) NULL;
ALTER TABLE file_object ADD COLUMN content_sha256 CHAR(64) NULL;
CREATE UNIQUE INDEX uk_file_object_sync_key ON file_object(project_id, sync_key);

CREATE TABLE project_file_sync_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    file_id CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_project_file_sync_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_project_file_sync_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_project_file_sync_request (project_id, requested_by, request_key)
);

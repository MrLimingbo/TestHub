CREATE TABLE api_debug_sync_archive (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    sync_kind VARCHAR(20) NOT NULL,
    source_project_id CHAR(26) NOT NULL,
    source_history_id CHAR(26) NOT NULL,
    source_revision CHAR(64) NOT NULL,
    payload_ciphertext MEDIUMTEXT NOT NULL,
    method VARCHAR(12) NOT NULL,
    protocol VARCHAR(20) NOT NULL,
    result VARCHAR(20) NOT NULL,
    request_target VARCHAR(2000) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    imported_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_debug_archive_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_debug_archive_user FOREIGN KEY (imported_by) REFERENCES user_account(id),
    UNIQUE KEY uk_api_debug_archive_origin (project_id,sync_kind,source_project_id,source_history_id)
);
CREATE TABLE api_debug_sync_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    sync_kind VARCHAR(20) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_json MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_debug_receipt_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_debug_receipt_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_api_debug_receipt_request (project_id,sync_kind,requested_by,request_key)
);

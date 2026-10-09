CREATE TABLE mobile_automation_transfer_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_automation_transfer_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_mobile_automation_transfer_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_mobile_automation_transfer_request (project_id, requested_by, request_key)
);

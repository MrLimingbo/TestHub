CREATE TABLE performance_artifact_archive (
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
    CONSTRAINT fk_perf_artifact_archive_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_perf_artifact_archive_user FOREIGN KEY (imported_by) REFERENCES user_account(id),
    UNIQUE KEY uk_perf_artifact_origin (project_id, source_project_id, source_execution_id, artifact_type)
);
CREATE TABLE performance_artifact_sync_receipt (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    artifact_id CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_perf_artifact_receipt_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_perf_artifact_receipt_user FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_perf_artifact_request (project_id, requested_by, request_key)
);
-- Independent durable intents deliberately have no FK to the project row locked by the enclosing import.
CREATE TABLE performance_artifact_transfer_intent (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    bucket VARCHAR(64) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX idx_perf_artifact_intent_created ON performance_artifact_transfer_intent(created_at);

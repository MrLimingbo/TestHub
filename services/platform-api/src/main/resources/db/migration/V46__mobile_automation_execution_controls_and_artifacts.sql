ALTER TABLE mobile_automation_execution
    ADD COLUMN cancel_requested_at TIMESTAMP(3) NULL AFTER deadline_at;

CREATE TABLE mobile_automation_execution_artifact (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    artifact_type VARCHAR(24) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_artifact_execution FOREIGN KEY (execution_id) REFERENCES mobile_automation_execution(id) ON DELETE CASCADE,
    CONSTRAINT fk_mobile_artifact_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_artifact_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mobile_artifact_checksum (execution_id, artifact_type, checksum),
    KEY idx_mobile_artifact_execution_created (execution_id, created_at)
) ENGINE=InnoDB;

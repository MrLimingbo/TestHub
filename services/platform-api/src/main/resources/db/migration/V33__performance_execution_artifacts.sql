CREATE TABLE performance_test_execution_artifact (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum CHAR(64) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_artifact_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_performance_test_execution_artifact_type (execution_id, artifact_type),
    UNIQUE KEY uk_performance_test_execution_artifact_key (object_key),
    KEY idx_performance_test_execution_artifact_project (project_id, execution_id, created_at)
) ENGINE=InnoDB;

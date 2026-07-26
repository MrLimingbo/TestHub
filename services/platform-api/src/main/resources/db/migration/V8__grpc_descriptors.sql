CREATE TABLE grpc_descriptor (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    source_format VARCHAR(24) NOT NULL,
    descriptor_hash CHAR(64) NOT NULL,
    descriptor_bytes MEDIUMBLOB NOT NULL,
    size_bytes INT NOT NULL,
    service_count INT NOT NULL,
    source_count INT NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_grpc_descriptor_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_grpc_descriptor_project_name (project_id, display_name),
    KEY idx_grpc_descriptor_org_created (organization_id, created_at),
    KEY idx_grpc_descriptor_project_created (project_id, created_at)
) ENGINE=InnoDB;

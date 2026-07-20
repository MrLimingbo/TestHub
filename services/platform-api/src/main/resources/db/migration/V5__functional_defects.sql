CREATE TABLE functional_defect (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    defect_code VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'MAJOR',
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    source VARCHAR(24) NOT NULL DEFAULT 'TESTHUB',
    external_url VARCHAR(1000) NULL,
    description TEXT NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_defect_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_functional_defect_code (project_id, defect_code),
    KEY idx_functional_defect_project (project_id, status, updated_at)
) ENGINE=InnoDB;

CREATE TABLE functional_defect_link (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    defect_id CHAR(26) NOT NULL,
    asset_type VARCHAR(24) NOT NULL,
    asset_id CHAR(26) NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_defect_link_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_functional_defect_link_defect FOREIGN KEY (defect_id) REFERENCES functional_defect(id) ON DELETE CASCADE,
    UNIQUE KEY uk_functional_defect_link (defect_id, asset_type, asset_id),
    KEY idx_functional_defect_link_asset (project_id, asset_type, asset_id)
) ENGINE=InnoDB;

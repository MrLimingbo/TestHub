CREATE TABLE functional_catalog (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    parent_id CHAR(26) NULL,
    name VARCHAR(160) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_catalog_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_functional_catalog_parent FOREIGN KEY (parent_id) REFERENCES functional_catalog(id),
    UNIQUE KEY uk_functional_catalog_name (project_id, parent_id, name),
    KEY idx_functional_catalog_project (project_id, parent_id, sort_order)
) ENGINE=InnoDB;

CREATE TABLE functional_requirement (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    catalog_id CHAR(26) NOT NULL,
    requirement_code VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'P1',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    description TEXT NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_requirement_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_functional_requirement_catalog FOREIGN KEY (catalog_id) REFERENCES functional_catalog(id),
    UNIQUE KEY uk_functional_requirement_code (project_id, requirement_code),
    KEY idx_functional_requirement_catalog (catalog_id, updated_at)
) ENGINE=InnoDB;

CREATE TABLE functional_case (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    catalog_id CHAR(26) NOT NULL,
    requirement_id CHAR(26) NULL,
    case_code VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'P1',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    precondition TEXT NOT NULL,
    steps TEXT NOT NULL,
    expected_result TEXT NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_case_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_functional_case_catalog FOREIGN KEY (catalog_id) REFERENCES functional_catalog(id),
    CONSTRAINT fk_functional_case_requirement FOREIGN KEY (requirement_id) REFERENCES functional_requirement(id),
    UNIQUE KEY uk_functional_case_code (project_id, case_code),
    KEY idx_functional_case_catalog (catalog_id, updated_at),
    KEY idx_functional_case_requirement (requirement_id)
) ENGINE=InnoDB;

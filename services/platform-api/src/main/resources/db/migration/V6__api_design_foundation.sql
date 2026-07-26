CREATE TABLE api_catalog (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    parent_id CHAR(26) NULL,
    name VARCHAR(160) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_catalog_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_catalog_parent FOREIGN KEY (parent_id) REFERENCES api_catalog(id),
    UNIQUE KEY uk_api_catalog_name (project_id, parent_id, name),
    KEY idx_api_catalog_project (project_id, parent_id, sort_order)
) ENGINE=InnoDB;

CREATE TABLE api_definition (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    catalog_id CHAR(26) NOT NULL,
    method VARCHAR(12) NOT NULL,
    path VARCHAR(700) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    current_version_id CHAR(26) NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_definition_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_definition_catalog FOREIGN KEY (catalog_id) REFERENCES api_catalog(id),
    UNIQUE KEY uk_api_definition_method_path (project_id, method, path),
    KEY idx_api_definition_catalog (catalog_id, updated_at)
) ENGINE=InnoDB;

CREATE TABLE api_definition_version (
    id CHAR(26) PRIMARY KEY,
    api_id CHAR(26) NOT NULL,
    version_name VARCHAR(40) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    request_headers JSON NOT NULL,
    request_query JSON NOT NULL,
    request_path JSON NOT NULL,
    request_body TEXT NOT NULL,
    response_body TEXT NOT NULL,
    example_response JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    published_by CHAR(26) NULL,
    published_at TIMESTAMP(3) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_definition_version_api FOREIGN KEY (api_id) REFERENCES api_definition(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_definition_version_name (api_id, version_name),
    KEY idx_api_definition_version_api (api_id, created_at)
) ENGINE=InnoDB;

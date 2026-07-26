CREATE TABLE functional_tag (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(64) NOT NULL,
    color VARCHAR(24) NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_tag_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_functional_tag_name (project_id, name),
    KEY idx_functional_tag_project (project_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE functional_asset_tag (
    id CHAR(26) PRIMARY KEY,
    project_id CHAR(26) NOT NULL,
    asset_type VARCHAR(24) NOT NULL,
    asset_id CHAR(26) NOT NULL,
    tag_id CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_asset_tag_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_functional_asset_tag_tag FOREIGN KEY (tag_id) REFERENCES functional_tag(id) ON DELETE CASCADE,
    UNIQUE KEY uk_functional_asset_tag (asset_type, asset_id, tag_id),
    KEY idx_functional_asset_tag_asset (project_id, asset_type, asset_id)
) ENGINE=InnoDB;

CREATE TABLE functional_custom_field (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    asset_type VARCHAR(24) NOT NULL,
    name VARCHAR(64) NOT NULL,
    field_key VARCHAR(64) NOT NULL,
    field_type VARCHAR(24) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    options_json JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_custom_field_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_functional_custom_field_key (project_id, asset_type, field_key),
    KEY idx_functional_custom_field_project (project_id, asset_type, updated_at)
) ENGINE=InnoDB;

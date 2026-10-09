-- Generated initial schema for a NEW desktop database at server schema V61.
-- Source SHA-256: 355a9fcad974a9f54605657850d58b639e1c5a901a6487e73bb5f890adc4f5c0
-- Ten data-upgrade statements are excluded because no older desktop database exists.
-- Do not regenerate after publishing; use subsequent versioned desktop migrations.
CREATE ALIAS JSON_LENGTH FOR "io.testhub.platform.desktop.DesktopJsonFunctions.length";
CREATE ALIAS JSON_SEARCH FOR "io.testhub.platform.desktop.DesktopJsonFunctions.search";

-- Source: V1__platform_foundation.sql
CREATE TABLE user_account (
    id CHAR(26) PRIMARY KEY,
    email VARCHAR(190) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_email (email)
) ENGINE=InnoDB;
CREATE TABLE organization (
    id CHAR(26) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_org_code (code)
) ENGINE=InnoDB;
CREATE TABLE project (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    code VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_project_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    UNIQUE KEY uk_project_org_code (organization_id, code)
) ENGINE=InnoDB;
CREATE TABLE membership (
    id CHAR(26) PRIMARY KEY,
    user_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NULL,
    role_code VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_member_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_member_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_membership_scope (user_id, organization_id, project_id, role_code),
    KEY idx_membership_project_user (project_id, user_id)
) ENGINE=InnoDB;
CREATE TABLE personal_access_token (
    id CHAR(26) PRIMARY KEY,
    user_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    token_prefix CHAR(12) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    scopes JSON NOT NULL,
    expires_at TIMESTAMP(3) NULL,
    last_used_at TIMESTAMP(3) NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_token_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_token_hash (token_hash)
) ENGINE=InnoDB;
CREATE TABLE project_parameter (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    environment_code VARCHAR(64) NOT NULL,
    parameter_key VARCHAR(128) NOT NULL,
    parameter_value TEXT NOT NULL,
    secret BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_parameter_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_parameter_scope (project_id, environment_code, parameter_key)
) ENGINE=InnoDB;
CREATE TABLE system_config (
    config_key VARCHAR(128) PRIMARY KEY,
    config_value TEXT NOT NULL,
    secret BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
CREATE TABLE audit_log (
    id CHAR(26) PRIMARY KEY,
    user_id CHAR(26) NULL,
    organization_id CHAR(26) NULL,
    project_id CHAR(26) NULL,
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(128) NULL,
    outcome VARCHAR(24) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    client_ip VARCHAR(64) NULL,
    detail JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_audit_scope_time (organization_id, project_id, created_at),
    KEY idx_audit_user_time (user_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE notification_template (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NULL,
    name VARCHAR(120) NOT NULL,
    event_code VARCHAR(80) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    subject_template VARCHAR(255) NOT NULL,
    body_template TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
CREATE TABLE execution_task (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    requested_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_task_project_time (project_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE file_object (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_file_object_key (object_key)
) ENGINE=InnoDB;

-- Source: V2__functional_foundation.sql
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

-- Source: V3__functional_execution.sql
CREATE TABLE functional_test_plan (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_code VARCHAR(40) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    description TEXT NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_plan_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_functional_plan_code (project_id, plan_code),
    KEY idx_functional_plan_project (project_id, updated_at)
) ENGINE=InnoDB;
CREATE TABLE functional_test_plan_case (
    id CHAR(26) PRIMARY KEY,
    plan_id CHAR(26) NOT NULL,
    case_id CHAR(26) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_plan_case_plan FOREIGN KEY (plan_id) REFERENCES functional_test_plan(id) ON DELETE CASCADE,
    CONSTRAINT fk_functional_plan_case_case FOREIGN KEY (case_id) REFERENCES functional_case(id),
    UNIQUE KEY uk_functional_plan_case (plan_id, case_id),
    KEY idx_functional_plan_case_order (plan_id, sort_order)
) ENGINE=InnoDB;
CREATE TABLE functional_test_run (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_id CHAR(26) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    created_by CHAR(26) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_functional_run_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_functional_run_plan FOREIGN KEY (plan_id) REFERENCES functional_test_plan(id),
    KEY idx_functional_run_project (project_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE functional_test_run_case (
    id CHAR(26) PRIMARY KEY,
    run_id CHAR(26) NOT NULL,
    case_id CHAR(26) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_RUN',
    note TEXT NOT NULL,
    executed_by CHAR(26) NULL,
    executed_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_functional_run_case_run FOREIGN KEY (run_id) REFERENCES functional_test_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_functional_run_case_case FOREIGN KEY (case_id) REFERENCES functional_case(id),
    UNIQUE KEY uk_functional_run_case (run_id, case_id),
    KEY idx_functional_run_case_status (run_id, status)
) ENGINE=InnoDB;
CREATE TABLE functional_test_run_step (
    id CHAR(26) PRIMARY KEY,
    run_case_id CHAR(26) NOT NULL,
    step_no INT NOT NULL,
    step_text TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NOT_RUN',
    actual_result TEXT NOT NULL,
    updated_by CHAR(26) NULL,
    updated_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_functional_run_step_case FOREIGN KEY (run_case_id) REFERENCES functional_test_run_case(id) ON DELETE CASCADE,
    UNIQUE KEY uk_functional_run_step (run_case_id, step_no)
) ENGINE=InnoDB;

-- Source: V4__functional_asset_governance.sql
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

-- Source: V5__functional_defects.sql
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

-- Source: V6__api_design_foundation.sql
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

-- Source: V7__api_debug_history.sql
CREATE TABLE api_debug_history (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    method VARCHAR(12) NOT NULL,
    request_url VARCHAR(2000) NOT NULL,
    response_status INT NULL,
    duration_ms INT NOT NULL,
    response_size INT NOT NULL DEFAULT 0,
    result VARCHAR(24) NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_debug_history_project FOREIGN KEY (project_id) REFERENCES project(id),
    KEY idx_api_debug_history_project_created (project_id, created_at),
    KEY idx_api_debug_history_org_created (organization_id, created_at)
) ENGINE=InnoDB;

-- Source: V8__grpc_descriptors.sql
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

-- Source: V9__mock_foundation.sql
CREATE TABLE mock_server (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    public_key CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mock_server_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mock_server_public_key (public_key),
    UNIQUE KEY uk_mock_server_project_name (project_id, name),
    KEY idx_mock_server_project_updated (project_id, updated_at)
) ENGINE=InnoDB;
CREATE TABLE mock_rule (
    id CHAR(26) PRIMARY KEY,
    server_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    http_method VARCHAR(12) NOT NULL,
    path VARCHAR(700) NOT NULL,
    response_status INT NOT NULL,
    response_headers JSON NOT NULL,
    response_body MEDIUMTEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mock_rule_server FOREIGN KEY (server_id) REFERENCES mock_server(id) ON DELETE CASCADE,
    CONSTRAINT fk_mock_rule_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mock_rule_method_path (server_id, http_method, path),
    KEY idx_mock_rule_project_server (project_id, server_id, updated_at)
) ENGINE=InnoDB;

-- Source: V10__mock_rule_conditions.sql
ALTER TABLE mock_rule ADD COLUMN priority INT NOT NULL DEFAULT 0 AFTER path;
ALTER TABLE mock_rule ADD COLUMN request_query JSON NULL AFTER priority;
ALTER TABLE mock_rule ADD COLUMN request_headers JSON NULL AFTER request_query;
ALTER TABLE mock_rule ADD COLUMN request_body_match VARCHAR(16) NOT NULL DEFAULT 'ANY' AFTER request_headers;
ALTER TABLE mock_rule ADD COLUMN request_body MEDIUMTEXT NULL AFTER request_body_match;
ALTER TABLE mock_rule MODIFY COLUMN request_query JSON NOT NULL;
ALTER TABLE mock_rule MODIFY COLUMN request_headers JSON NOT NULL;
ALTER TABLE mock_rule DROP INDEX uk_mock_rule_method_path;
ALTER TABLE mock_rule ADD KEY idx_mock_rule_runtime (server_id, http_method, path, enabled, priority);

-- Source: V11__mock_smart_examples.sql
ALTER TABLE mock_server ADD COLUMN smart_example_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;

-- Source: V12__mock_shares.sql
CREATE TABLE mock_share (
    id CHAR(26) PRIMARY KEY,
    server_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    share_key CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    expires_at TIMESTAMP(3) NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mock_share_server FOREIGN KEY (server_id) REFERENCES mock_server(id) ON DELETE CASCADE,
    CONSTRAINT fk_mock_share_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mock_share_key (share_key),
    KEY idx_mock_share_project_server (project_id, server_id, created_at),
    KEY idx_mock_share_active_key (share_key, revoked_at, expires_at)
) ENGINE=InnoDB;

-- Source: V13__api_automation_scenarios.sql
CREATE TABLE api_automation_scenario (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1200) NOT NULL DEFAULT '',
    environment_code VARCHAR(64) NOT NULL DEFAULT 'default',
    failure_strategy VARCHAR(24) NOT NULL DEFAULT 'STOP',
    version INT NOT NULL DEFAULT 1,
    steps JSON NOT NULL,
    variables JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_scenario_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_scenario_name (project_id, name),
    KEY idx_api_automation_scenario_project_updated (project_id, updated_at)
) ENGINE=InnoDB;
CREATE TABLE api_automation_scenario_revision (
    id CHAR(26) PRIMARY KEY,
    scenario_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    version INT NOT NULL,
    snapshot JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_revision_scenario FOREIGN KEY (scenario_id) REFERENCES api_automation_scenario(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_automation_revision_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_revision_version (scenario_id, version),
    KEY idx_api_automation_revision_project_scenario (project_id, scenario_id, version)
) ENGINE=InnoDB;

-- Source: V14__api_automation_execution_queue.sql
CREATE TABLE api_automation_execution (
    id CHAR(26) PRIMARY KEY,
    task_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    scenario_name VARCHAR(160) NOT NULL,
    scenario_version INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    status_code VARCHAR(80) NULL,
    status_message VARCHAR(512) NOT NULL DEFAULT '',
    manifest_ref VARCHAR(512) NOT NULL,
    runner_id VARCHAR(64) NULL,
    requested_by CHAR(26) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    deadline_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_automation_execution_scenario FOREIGN KEY (scenario_id) REFERENCES api_automation_scenario(id),
    UNIQUE KEY uk_api_automation_execution_task (task_id),
    KEY idx_api_automation_execution_project_time (project_id, created_at),
    KEY idx_api_automation_execution_status_time (status, created_at)
) ENGINE=InnoDB;
CREATE TABLE api_runner_outbox (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    stream_key VARCHAR(160) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    dispatch_attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at TIMESTAMP(3) NULL,
    last_error VARCHAR(512) NULL,
    sent_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_runner_outbox_execution FOREIGN KEY (execution_id) REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    KEY idx_api_runner_outbox_dispatch (status, available_at, created_at),
    KEY idx_api_runner_outbox_execution (execution_id)
) ENGINE=InnoDB;

-- Source: V15__api_automation_execution_controls.sql
ALTER TABLE api_runner_outbox ADD COLUMN message_type VARCHAR(24) NOT NULL DEFAULT 'TASK' AFTER execution_id;
ALTER TABLE api_runner_outbox ADD COLUMN max_dispatch_attempts INT NOT NULL DEFAULT 5 AFTER dispatch_attempts;
ALTER TABLE api_runner_outbox ADD KEY idx_api_runner_outbox_execution_type (execution_id, message_type);

-- Source: V16__api_automation_execution_logs.sql
CREATE TABLE api_automation_execution_log (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    source VARCHAR(24) NOT NULL,
    runner_id VARCHAR(64) NULL,
    level VARCHAR(12) NOT NULL,
    code VARCHAR(80) NOT NULL,
    message VARCHAR(512) NOT NULL,
    stream_record_id VARCHAR(64) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_log_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_log_stream (stream_record_id),
    KEY idx_api_automation_execution_log_project_time (project_id, occurred_at, id),
    KEY idx_api_automation_execution_log_execution_time (execution_id, occurred_at, id)
) ENGINE=InnoDB;

-- Source: V17__api_automation_execution_manifests.sql
CREATE TABLE api_automation_execution_manifest (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    scenario_version INT NOT NULL,
    content JSON NOT NULL,
    checksum CHAR(64) NOT NULL,
    step_count INT NOT NULL,
    variable_count INT NOT NULL,
    secret_reference_count INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_manifest_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_manifest_execution (execution_id),
    KEY idx_api_automation_execution_manifest_project_time (project_id, created_at),
    KEY idx_api_automation_execution_manifest_scenario (scenario_id, scenario_version)
) ENGINE=InnoDB;

-- Source: V18__api_automation_execution_runtime_snapshots.sql
CREATE TABLE api_automation_execution_runtime_snapshot (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    content JSON NOT NULL,
    checksum CHAR(64) NOT NULL,
    variable_count INT NOT NULL,
    withheld_secret_count INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_runtime_snapshot_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_runtime_snapshot_execution (execution_id),
    KEY idx_api_automation_execution_runtime_snapshot_project_time (project_id, created_at)
) ENGINE=InnoDB;

-- Source: V19__api_automation_execution_http_results.sql
CREATE TABLE api_automation_execution_http_result (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    request_sequence INT UNSIGNED NOT NULL,
    method VARCHAR(8) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    status_code SMALLINT UNSIGNED NULL,
    duration_millis INT UNSIGNED NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    stream_record_id VARCHAR(64) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_http_result_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_http_result_stream (stream_record_id),
    UNIQUE KEY uk_api_automation_execution_http_result_sequence (execution_id, request_sequence),
    KEY idx_api_automation_execution_http_result_project_time (project_id, occurred_at, id),
    KEY idx_api_automation_execution_http_result_execution_time (execution_id, request_sequence)
) ENGINE=InnoDB;

-- Source: V20__api_automation_execution_step_results.sql
CREATE TABLE api_automation_execution_step_result (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    step_sequence INT UNSIGNED NOT NULL,
    step_type VARCHAR(16) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    observed_status_code SMALLINT UNSIGNED NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    stream_record_id VARCHAR(64) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_step_result_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_automation_execution_step_result_stream (stream_record_id),
    UNIQUE KEY uk_api_automation_execution_step_result_sequence (execution_id, step_sequence),
    KEY idx_api_automation_execution_step_result_project_time (project_id, occurred_at, id),
    KEY idx_api_automation_execution_step_result_execution_time (execution_id, step_sequence)
) ENGINE=InnoDB;

-- Source: V21__api_automation_webhook_triggers.sql
CREATE TABLE api_automation_webhook_trigger (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    secret_ciphertext VARCHAR(768) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_webhook_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_automation_webhook_scenario FOREIGN KEY (scenario_id) REFERENCES api_automation_scenario(id),
    CONSTRAINT fk_api_automation_webhook_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    UNIQUE KEY uk_api_automation_webhook_project_name (project_id, name),
    KEY idx_api_automation_webhook_project_updated (project_id, updated_at),
    KEY idx_api_automation_webhook_scenario (scenario_id)
) ENGINE=InnoDB;

-- Source: V22__api_automation_schedules.sql
CREATE TABLE api_automation_schedule (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    cron_expression VARCHAR(160) NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    timeout_seconds INT NOT NULL DEFAULT 300,
    max_consecutive_failures INT NOT NULL DEFAULT 3,
    consecutive_failures INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_execution_id CHAR(26) NULL,
    last_execution_status VARCHAR(32) NULL,
    last_triggered_at TIMESTAMP(3) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_schedule_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_automation_schedule_scenario FOREIGN KEY (scenario_id) REFERENCES api_automation_scenario(id),
    CONSTRAINT fk_api_automation_schedule_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    UNIQUE KEY uk_api_automation_schedule_project_name (project_id, name),
    KEY idx_api_automation_schedule_project_updated (project_id, updated_at),
    KEY idx_api_automation_schedule_enabled (enabled, updated_at),
    KEY idx_api_automation_schedule_last_execution (last_execution_id)
) ENGINE=InnoDB;

-- Source: V23__api_automation_data_sets.sql
CREATE TABLE api_automation_data_set (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    source_format VARCHAR(12) NOT NULL,
    columns JSON NOT NULL,
    data_rows JSON NOT NULL,
    row_count INT NOT NULL,
    checksum CHAR(64) NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_data_set_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_automation_data_set_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_api_automation_data_set_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT uk_api_automation_data_set_project_name UNIQUE (project_id, name),
    KEY idx_api_automation_data_set_project_updated (project_id, updated_at)
) ENGINE=InnoDB;
ALTER TABLE api_automation_execution ADD COLUMN data_set_id CHAR(26) NULL AFTER scenario_version;
ALTER TABLE api_automation_execution ADD COLUMN data_set_row_number INT NULL AFTER data_set_id;
ALTER TABLE api_automation_execution ADD CONSTRAINT fk_api_automation_execution_data_set
        FOREIGN KEY (data_set_id) REFERENCES api_automation_data_set(id) ON DELETE SET NULL;
ALTER TABLE api_automation_execution ADD KEY idx_api_automation_execution_data_set (data_set_id, data_set_row_number);

-- Source: V24__api_automation_scenario_scripts.sql
ALTER TABLE api_automation_scenario ADD COLUMN pre_request_script MEDIUMTEXT NULL AFTER variables;
ALTER TABLE api_automation_scenario ADD COLUMN post_response_script MEDIUMTEXT NULL AFTER pre_request_script;

-- Source: V25__api_automation_reusable_steps.sql
CREATE TABLE api_automation_reusable_step (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1200) NOT NULL DEFAULT '',
    version INT NOT NULL DEFAULT 1,
    steps JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_reusable_step_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_reusable_step_name (project_id, name),
    KEY idx_api_automation_reusable_step_project_updated (project_id, updated_at)
) ENGINE=InnoDB;
CREATE TABLE api_automation_reusable_step_revision (
    id CHAR(26) PRIMARY KEY,
    reusable_step_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    version INT NOT NULL,
    snapshot JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_reusable_step_revision_step
        FOREIGN KEY (reusable_step_id) REFERENCES api_automation_reusable_step(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_automation_reusable_step_revision_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_reusable_step_revision_version (reusable_step_id, version),
    KEY idx_api_automation_reusable_step_revision_project (project_id, reusable_step_id, version)
) ENGINE=InnoDB;

-- Source: V26__api_automation_execution_profiles.sql
CREATE TABLE api_automation_execution_profile (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    environment_code VARCHAR(64) NOT NULL,
    variables JSON NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_automation_execution_profile_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_api_automation_execution_profile_name (project_id, name),
    KEY idx_api_automation_execution_profile_project_updated (project_id, updated_at)
) ENGINE=InnoDB;
ALTER TABLE api_automation_execution ADD COLUMN execution_profile_id CHAR(26) NULL AFTER scenario_version;
ALTER TABLE api_automation_execution ADD COLUMN execution_profile_version INT NULL AFTER execution_profile_id;
ALTER TABLE api_automation_execution ADD KEY idx_api_automation_execution_profile (project_id, execution_profile_id, execution_profile_version);

-- Source: V27__api_automation_execution_script_results.sql
CREATE TABLE api_automation_execution_script_result (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    script_phase VARCHAR(24) NOT NULL,
    test_count INT NOT NULL,
    passed_count INT NOT NULL,
    failed_count INT NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    stream_record_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_api_automation_execution_script_stream (stream_record_id),
    KEY idx_api_automation_execution_script_execution (project_id, execution_id, occurred_at),
    CONSTRAINT ck_api_automation_execution_script_phase CHECK (script_phase IN ('PRE_REQUEST', 'POST_RESPONSE')),
    CONSTRAINT ck_api_automation_execution_script_test_count CHECK (test_count BETWEEN 1 AND 100),
    CONSTRAINT ck_api_automation_execution_script_totals CHECK (passed_count >= 0 AND failed_count >= 0 AND passed_count + failed_count = test_count),
    CONSTRAINT fk_api_automation_execution_script_execution FOREIGN KEY (execution_id)
        REFERENCES api_automation_execution(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Source: V28__api_automation_execution_http_result_attempts.sql
ALTER TABLE api_automation_execution_http_result ADD COLUMN attempt_number TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER request_sequence;
ALTER TABLE api_automation_execution_http_result DROP INDEX uk_api_automation_execution_http_result_sequence;
ALTER TABLE api_automation_execution_http_result ADD UNIQUE KEY uk_api_automation_execution_http_result_attempt (execution_id, request_sequence, attempt_number);
ALTER TABLE api_automation_execution_http_result DROP INDEX idx_api_automation_execution_http_result_execution_time;
ALTER TABLE api_automation_execution_http_result ADD KEY idx_api_automation_execution_http_result_execution_time (execution_id, request_sequence, attempt_number);

-- Source: V29__api_automation_data_set_source_file.sql
ALTER TABLE api_automation_data_set ADD COLUMN source_file_id CHAR(26) NULL AFTER checksum;
ALTER TABLE api_automation_data_set ADD CONSTRAINT fk_api_automation_data_set_source_file
        FOREIGN KEY (source_file_id) REFERENCES file_object(id) ON DELETE SET NULL;
ALTER TABLE api_automation_data_set ADD KEY idx_api_automation_data_set_source_file (source_file_id);

-- Source: V30__performance_test_plans.sql
CREATE TABLE performance_test_plan (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_plan_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_performance_test_plan_name (project_id, name),
    KEY idx_performance_test_plan_project_updated (project_id, updated_at)
) ENGINE=InnoDB;
CREATE TABLE performance_test_thread_group (
    id CHAR(26) PRIMARY KEY,
    plan_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    load_model VARCHAR(24) NOT NULL,
    thread_count INT NOT NULL,
    ramp_up_seconds INT NOT NULL,
    duration_seconds INT NOT NULL,
    target_tps INT NULL,
    p95_threshold_millis INT NULL,
    error_threshold_percent DECIMAL(5,2) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_thread_group_plan FOREIGN KEY (plan_id) REFERENCES performance_test_plan(id) ON DELETE CASCADE,
    CONSTRAINT fk_performance_test_thread_group_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_performance_test_thread_group_name (plan_id, name),
    KEY idx_performance_test_thread_group_project_plan (project_id, plan_id, sort_order)
) ENGINE=InnoDB;

-- Source: V31__performance_test_execution_queue.sql
CREATE TABLE performance_test_execution (
    id CHAR(26) PRIMARY KEY,
    task_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_id CHAR(26) NOT NULL,
    plan_name VARCHAR(160) NOT NULL,
    plan_version INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    status_code VARCHAR(80) NOT NULL DEFAULT 'QUEUED',
    status_message VARCHAR(512) NOT NULL DEFAULT '任务已进入安全投递队列。',
    manifest_ref VARCHAR(256) NOT NULL,
    runner_id VARCHAR(64) NULL,
    requested_by CHAR(26) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    deadline_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_performance_test_execution_task (task_id),
    KEY idx_performance_test_execution_project_created (project_id, created_at),
    KEY idx_performance_test_execution_active_deadline (status, deadline_at)
) ENGINE=InnoDB;
CREATE TABLE performance_test_execution_manifest (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    plan_id CHAR(26) NOT NULL,
    plan_version INT NOT NULL,
    content JSON NOT NULL,
    checksum CHAR(64) NOT NULL,
    thread_group_count INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_manifest_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_performance_test_execution_manifest_execution (execution_id),
    KEY idx_performance_test_execution_manifest_project (project_id, execution_id)
) ENGINE=InnoDB;
CREATE TABLE performance_test_execution_log (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    source VARCHAR(24) NOT NULL,
    runner_id VARCHAR(64) NULL,
    level VARCHAR(16) NOT NULL,
    code VARCHAR(80) NOT NULL,
    message VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_log_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_test_execution_log_execution (project_id, execution_id, occurred_at, id)
) ENGINE=InnoDB;
CREATE TABLE performance_runner_outbox (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    message_type VARCHAR(24) NOT NULL,
    stream_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    dispatch_attempts INT NOT NULL DEFAULT 0,
    max_dispatch_attempts INT NOT NULL DEFAULT 5,
    available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at TIMESTAMP(3) NULL,
    sent_at TIMESTAMP(3) NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_runner_outbox_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_runner_outbox_dispatch (status, available_at, created_at),
    KEY idx_performance_runner_outbox_execution_type (execution_id, message_type)
) ENGINE=InnoDB;

-- Source: V32__performance_execution_metrics.sql
CREATE TABLE performance_test_execution_metric (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    thread_group_name VARCHAR(160) NOT NULL,
    sample_count BIGINT NOT NULL,
    success_count BIGINT NOT NULL,
    error_count BIGINT NOT NULL,
    active_threads INT NOT NULL,
    throughput_per_second DECIMAL(14,4) NOT NULL,
    average_latency_millis INT NOT NULL,
    p95_latency_millis INT NOT NULL,
    min_latency_millis INT NOT NULL,
    max_latency_millis INT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_metric_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_test_execution_metric_series (project_id, execution_id, occurred_at, id)
) ENGINE=InnoDB;

-- Source: V33__performance_execution_artifacts.sql
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

-- Source: V34__performance_runner_capacity_routing.sql
ALTER TABLE performance_test_execution ADD COLUMN routing_attempts INT NOT NULL DEFAULT 0 AFTER runner_id;
ALTER TABLE performance_test_execution ADD KEY idx_performance_test_execution_runner_active (runner_id, status, updated_at);

-- Source: V35__performance_thread_group_requests.sql
ALTER TABLE performance_test_thread_group ADD COLUMN request_method VARCHAR(10) NOT NULL DEFAULT 'GET' AFTER target_url;
ALTER TABLE performance_test_thread_group ADD COLUMN request_headers MEDIUMTEXT NOT NULL DEFAULT ('{}') AFTER request_method;
ALTER TABLE performance_test_thread_group ADD COLUMN request_body MEDIUMTEXT NULL AFTER request_headers;
ALTER TABLE performance_test_thread_group ADD COLUMN expected_status_code SMALLINT UNSIGNED NULL AFTER request_body;
ALTER TABLE performance_test_thread_group ADD COLUMN response_contains VARCHAR(1000) NULL AFTER expected_status_code;

-- Source: V36__performance_thread_group_data_sets.sql
ALTER TABLE performance_test_thread_group ADD COLUMN data_set_csv MEDIUMTEXT NULL AFTER response_contains;

-- Source: V37__performance_thread_group_rendezvous.sql
ALTER TABLE performance_test_thread_group ADD COLUMN rendezvous_users INT UNSIGNED NULL AFTER data_set_csv;

-- Source: V38__performance_execution_error_summaries.sql
CREATE TABLE performance_test_execution_error (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    thread_group_name VARCHAR(160) NOT NULL,
    response_code VARCHAR(64) NOT NULL,
    error_count BIGINT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_error_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    UNIQUE KEY uk_performance_test_execution_error_summary (execution_id, runner_id, thread_group_name, response_code),
    KEY idx_performance_test_execution_error_report (project_id, execution_id, error_count, occurred_at)
) ENGINE=InnoDB;

-- Source: V39__performance_thread_group_query_parameters.sql
ALTER TABLE performance_test_thread_group ADD COLUMN query_parameters MEDIUMTEXT NOT NULL DEFAULT ('{}') AFTER request_headers;

-- Source: V40__performance_thread_group_stepped_ramp.sql
ALTER TABLE performance_test_thread_group ADD COLUMN step_count SMALLINT UNSIGNED NULL AFTER ramp_up_seconds;
ALTER TABLE performance_test_thread_group ADD COLUMN step_interval_seconds INT UNSIGNED NULL AFTER step_count;

-- Source: V41__performance_execution_resource_metrics.sql
CREATE TABLE performance_test_execution_resource_metric (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    cpu_percent DECIMAL(9,4) NOT NULL,
    resident_memory_bytes BIGINT UNSIGNED NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_performance_test_execution_resource_metric_execution FOREIGN KEY (execution_id)
        REFERENCES performance_test_execution(id) ON DELETE CASCADE,
    KEY idx_performance_test_execution_resource_metric_series (project_id, execution_id, occurred_at, id)
) ENGINE=InnoDB;

-- Source: V42__quality_report_schedules_and_notification_delivery.sql
ALTER TABLE notification_template ADD COLUMN target_url VARCHAR(2048) NULL AFTER body_template;
ALTER TABLE notification_template ADD COLUMN signing_secret_ciphertext TEXT NULL AFTER target_url;
CREATE TABLE quality_report_schedule (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    project_ids JSON NOT NULL,
    cron_expression VARCHAR(160) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    period_days INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_id CHAR(26) NULL,
    last_run_status VARCHAR(24) NULL,
    last_run_at TIMESTAMP(3) NULL,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_quality_report_schedule_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_quality_report_schedule_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    UNIQUE KEY uk_quality_report_schedule_org_name (organization_id, name),
    KEY idx_quality_report_schedule_enabled (enabled, updated_at)
) ENGINE=InnoDB;
CREATE TABLE quality_report_schedule_run (
    id CHAR(26) PRIMARY KEY,
    schedule_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_ids JSON NOT NULL,
    range_from DATE NOT NULL,
    range_to DATE NOT NULL,
    status VARCHAR(24) NOT NULL,
    delivery_count INT NOT NULL DEFAULT 0,
    summary JSON NOT NULL,
    error_message VARCHAR(512) NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_quality_report_schedule_run_schedule FOREIGN KEY (schedule_id) REFERENCES quality_report_schedule(id) ON DELETE CASCADE,
    CONSTRAINT fk_quality_report_schedule_run_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    KEY idx_quality_report_schedule_run_schedule (schedule_id, started_at DESC),
    KEY idx_quality_report_schedule_run_org (organization_id, started_at DESC)
) ENGINE=InnoDB;
CREATE TABLE notification_delivery (
    id CHAR(26) PRIMARY KEY,
    schedule_run_id CHAR(26) NOT NULL,
    template_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    template_name VARCHAR(120) NOT NULL,
    event_code VARCHAR(80) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    signing_secret_ciphertext TEXT NOT NULL,
    subject VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at TIMESTAMP(3) NULL,
    response_status INT NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_notification_delivery_run FOREIGN KEY (schedule_run_id) REFERENCES quality_report_schedule_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_delivery_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_notification_delivery_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_notification_delivery_dedupe (schedule_run_id, template_id, event_code),
    KEY idx_notification_delivery_dispatch (status, next_attempt_at, created_at),
    KEY idx_notification_delivery_project (project_id, created_at DESC)
) ENGINE=InnoDB;

-- Source: V43__web_ui_automation_foundation.sql
CREATE TABLE ui_automation_browser_profile (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    browser VARCHAR(16) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    viewport_width INT NOT NULL DEFAULT 1440,
    viewport_height INT NOT NULL DEFAULT 900,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_browser_profile_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_browser_profile_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_browser_profile_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_ui_browser_profile_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_browser_profile_project_name (project_id, name),
    KEY idx_ui_browser_profile_project_updated (project_id, updated_at DESC)
) ENGINE=InnoDB;
CREATE TABLE ui_automation_page_object (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    selector_value VARCHAR(1024) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_page_object_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_page_object_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_page_object_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_ui_page_object_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_page_object_project_name (project_id, name),
    KEY idx_ui_page_object_project_updated (project_id, updated_at DESC)
) ENGINE=InnoDB;
CREATE TABLE ui_automation_scenario (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    browser_profile_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    failure_strategy VARCHAR(16) NOT NULL DEFAULT 'STOP',
    version INT NOT NULL DEFAULT 1,
    variables JSON NOT NULL,
    steps JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_scenario_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_scenario_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_scenario_profile FOREIGN KEY (browser_profile_id) REFERENCES ui_automation_browser_profile(id),
    CONSTRAINT fk_ui_scenario_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_ui_scenario_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_scenario_project_name (project_id, name),
    KEY idx_ui_scenario_project_updated (project_id, updated_at DESC)
) ENGINE=InnoDB;
CREATE TABLE ui_automation_execution (
    id CHAR(26) PRIMARY KEY,
    task_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    scenario_name VARCHAR(160) NOT NULL,
    scenario_version INT NOT NULL,
    browser_profile_id CHAR(26) NOT NULL,
    browser_profile_name VARCHAR(120) NOT NULL,
    browser VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    status_code VARCHAR(80) NULL,
    status_message VARCHAR(512) NULL,
    runner_id VARCHAR(64) NULL,
    manifest_ref VARCHAR(256) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    deadline_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_execution_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_execution_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_execution_scenario FOREIGN KEY (scenario_id) REFERENCES ui_automation_scenario(id),
    CONSTRAINT fk_ui_execution_profile FOREIGN KEY (browser_profile_id) REFERENCES ui_automation_browser_profile(id),
    CONSTRAINT fk_ui_execution_requester FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_execution_task (task_id),
    KEY idx_ui_execution_project_requested (project_id, requested_at DESC),
    KEY idx_ui_execution_active_deadline (status, deadline_at)
) ENGINE=InnoDB;
CREATE TABLE ui_automation_execution_manifest (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    scenario_version INT NOT NULL,
    content JSON NOT NULL,
    checksum CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_execution_manifest_execution FOREIGN KEY (execution_id) REFERENCES ui_automation_execution(id) ON DELETE CASCADE,
    CONSTRAINT fk_ui_execution_manifest_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_execution_manifest_project FOREIGN KEY (project_id) REFERENCES project(id),
    KEY idx_ui_execution_manifest_execution (execution_id)
) ENGINE=InnoDB;
CREATE TABLE ui_automation_execution_step_result (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    step_sequence INT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    step_name VARCHAR(160) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    duration_millis INT NOT NULL,
    error_message VARCHAR(512) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_step_result_execution FOREIGN KEY (execution_id) REFERENCES ui_automation_execution(id) ON DELETE CASCADE,
    CONSTRAINT fk_ui_step_result_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_step_result_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_ui_step_result_execution_sequence (execution_id, step_sequence),
    KEY idx_ui_step_result_execution_occurred (execution_id, occurred_at)
) ENGINE=InnoDB;
CREATE TABLE ui_automation_execution_artifact (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum CHAR(64) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_artifact_execution FOREIGN KEY (execution_id) REFERENCES ui_automation_execution(id) ON DELETE CASCADE,
    CONSTRAINT fk_ui_artifact_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_artifact_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_ui_artifact_execution_type_checksum (execution_id, artifact_type, checksum),
    KEY idx_ui_artifact_execution_created (execution_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE ui_automation_runner_outbox (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    stream_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    dispatch_attempts INT NOT NULL DEFAULT 0,
    max_dispatch_attempts INT NOT NULL DEFAULT 5,
    available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at TIMESTAMP(3) NULL,
    sent_at TIMESTAMP(3) NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_runner_outbox_execution FOREIGN KEY (execution_id) REFERENCES ui_automation_execution(id) ON DELETE CASCADE,
    KEY idx_ui_runner_outbox_dispatch (status, available_at, created_at)
) ENGINE=InnoDB;

-- Source: V44__web_ui_automation_schedules_retries_and_plan_links.sql
CREATE TABLE ui_automation_schedule (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    functional_plan_id CHAR(26) NULL,
    name VARCHAR(160) NOT NULL,
    cron_expression VARCHAR(160) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    timeout_seconds INT NOT NULL DEFAULT 300,
    max_retry_attempts INT NOT NULL DEFAULT 0,
    retry_backoff_seconds INT NOT NULL DEFAULT 30,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_execution_id CHAR(26) NULL,
    last_execution_status VARCHAR(24) NULL,
    last_triggered_at TIMESTAMP(3) NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ui_schedule_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_ui_schedule_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_ui_schedule_scenario FOREIGN KEY (scenario_id) REFERENCES ui_automation_scenario(id),
    CONSTRAINT fk_ui_schedule_plan FOREIGN KEY (functional_plan_id) REFERENCES functional_test_plan(id) ON DELETE SET NULL,
    CONSTRAINT fk_ui_schedule_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_ui_schedule_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_ui_schedule_project_name (project_id, name),
    KEY idx_ui_schedule_project_enabled (project_id, enabled, updated_at DESC)
) ENGINE=InnoDB;
ALTER TABLE ui_automation_execution ADD COLUMN schedule_id CHAR(26) NULL AFTER browser;
ALTER TABLE ui_automation_execution ADD COLUMN functional_plan_id CHAR(26) NULL AFTER schedule_id;
ALTER TABLE ui_automation_execution ADD COLUMN functional_plan_name VARCHAR(200) NULL AFTER functional_plan_id;
ALTER TABLE ui_automation_execution ADD COLUMN retry_of_execution_id CHAR(26) NULL AFTER functional_plan_name;
ALTER TABLE ui_automation_execution ADD COLUMN retry_attempt INT NOT NULL DEFAULT 0 AFTER retry_of_execution_id;
ALTER TABLE ui_automation_execution ADD CONSTRAINT fk_ui_execution_schedule FOREIGN KEY (schedule_id) REFERENCES ui_automation_schedule(id) ON DELETE SET NULL;
ALTER TABLE ui_automation_execution ADD CONSTRAINT fk_ui_execution_plan FOREIGN KEY (functional_plan_id) REFERENCES functional_test_plan(id) ON DELETE SET NULL;
ALTER TABLE ui_automation_execution ADD CONSTRAINT fk_ui_execution_retry_parent FOREIGN KEY (retry_of_execution_id) REFERENCES ui_automation_execution(id) ON DELETE SET NULL;
ALTER TABLE ui_automation_execution ADD KEY idx_ui_execution_schedule_requested (schedule_id, requested_at DESC);
ALTER TABLE ui_automation_execution ADD KEY idx_ui_execution_plan_requested (functional_plan_id, requested_at DESC);

-- Source: V45__mobile_automation_foundation.sql
CREATE TABLE mobile_automation_device (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    udid VARCHAR(255) NOT NULL,
    automation_name VARCHAR(64) NOT NULL,
    capabilities JSON NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_device_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_device_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_mobile_device_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_mobile_device_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_mobile_device_project_name (project_id, name),
    UNIQUE KEY uk_mobile_device_project_udid (project_id, udid),
    KEY idx_mobile_device_project_updated (project_id, updated_at DESC)
) ENGINE=InnoDB;
CREATE TABLE mobile_automation_application (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    app_identifier VARCHAR(255) NOT NULL,
    version_name VARCHAR(120) NOT NULL DEFAULT '',
    app_file_id CHAR(26) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_application_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_application_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_mobile_application_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_mobile_application_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_mobile_application_project_name (project_id, name),
    KEY idx_mobile_application_project_updated (project_id, updated_at DESC)
) ENGINE=InnoDB;
CREATE TABLE mobile_automation_element (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    locator_strategy VARCHAR(32) NOT NULL,
    locator_value VARCHAR(1024) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_element_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_element_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_mobile_element_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_mobile_element_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_mobile_element_project_name (project_id, name),
    KEY idx_mobile_element_project_updated (project_id, updated_at DESC)
) ENGINE=InnoDB;
CREATE TABLE mobile_automation_scenario (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    device_id CHAR(26) NOT NULL,
    application_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    failure_strategy VARCHAR(16) NOT NULL DEFAULT 'STOP',
    version INT NOT NULL DEFAULT 1,
    variables JSON NOT NULL,
    steps JSON NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_scenario_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_scenario_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_mobile_scenario_device FOREIGN KEY (device_id) REFERENCES mobile_automation_device(id),
    CONSTRAINT fk_mobile_scenario_application FOREIGN KEY (application_id) REFERENCES mobile_automation_application(id),
    CONSTRAINT fk_mobile_scenario_creator FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_mobile_scenario_updater FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_mobile_scenario_project_name (project_id, name),
    KEY idx_mobile_scenario_project_updated (project_id, updated_at DESC)
) ENGINE=InnoDB;
CREATE TABLE mobile_automation_execution (
    id CHAR(26) PRIMARY KEY,
    task_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    scenario_name VARCHAR(160) NOT NULL,
    scenario_version INT NOT NULL,
    device_id CHAR(26) NOT NULL,
    device_name VARCHAR(120) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    application_id CHAR(26) NOT NULL,
    application_name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    status_code VARCHAR(80) NULL,
    status_message VARCHAR(512) NULL,
    runner_id VARCHAR(64) NULL,
    manifest_ref VARCHAR(256) NOT NULL,
    requested_by CHAR(26) NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    deadline_at TIMESTAMP(3) NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_execution_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_execution_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_mobile_execution_scenario FOREIGN KEY (scenario_id) REFERENCES mobile_automation_scenario(id),
    CONSTRAINT fk_mobile_execution_device FOREIGN KEY (device_id) REFERENCES mobile_automation_device(id),
    CONSTRAINT fk_mobile_execution_application FOREIGN KEY (application_id) REFERENCES mobile_automation_application(id),
    CONSTRAINT fk_mobile_execution_requester FOREIGN KEY (requested_by) REFERENCES user_account(id),
    UNIQUE KEY uk_mobile_execution_task (task_id),
    KEY idx_mobile_execution_project_requested (project_id, requested_at DESC),
    KEY idx_mobile_execution_active_deadline (status, deadline_at)
) ENGINE=InnoDB;
CREATE TABLE mobile_automation_execution_manifest (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    scenario_id CHAR(26) NOT NULL,
    scenario_version INT NOT NULL,
    content JSON NOT NULL,
    checksum CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_manifest_execution FOREIGN KEY (execution_id) REFERENCES mobile_automation_execution(id) ON DELETE CASCADE,
    CONSTRAINT fk_mobile_manifest_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_manifest_project FOREIGN KEY (project_id) REFERENCES project(id),
    KEY idx_mobile_manifest_execution (execution_id)
) ENGINE=InnoDB;
CREATE TABLE mobile_automation_execution_step_result (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    runner_id VARCHAR(64) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    step_sequence INT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    step_name VARCHAR(160) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    duration_millis INT NOT NULL,
    error_message VARCHAR(512) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_step_execution FOREIGN KEY (execution_id) REFERENCES mobile_automation_execution(id) ON DELETE CASCADE,
    CONSTRAINT fk_mobile_step_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_mobile_step_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_mobile_step_execution_sequence (execution_id, step_sequence),
    KEY idx_mobile_step_execution_occurred (execution_id, occurred_at)
) ENGINE=InnoDB;
CREATE TABLE mobile_automation_runner_outbox (
    id CHAR(26) PRIMARY KEY,
    execution_id CHAR(26) NOT NULL,
    stream_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    dispatch_attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at TIMESTAMP(3) NULL,
    sent_at TIMESTAMP(3) NULL,
    last_error VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_mobile_outbox_execution FOREIGN KEY (execution_id) REFERENCES mobile_automation_execution(id) ON DELETE CASCADE,
    KEY idx_mobile_outbox_dispatch (status, available_at, created_at)
) ENGINE=InnoDB;

-- Source: V46__mobile_automation_execution_controls_and_artifacts.sql
ALTER TABLE mobile_automation_execution ADD COLUMN cancel_requested_at TIMESTAMP(3) NULL AFTER deadline_at;
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

-- Source: V47__extension_registry_and_enterprise_adapter_foundation.sql
CREATE TABLE plugin_signing_key (
    id CHAR(26) PRIMARY KEY,
    key_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    public_key_base64 VARCHAR(1024) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at TIMESTAMP(3) NULL,
    UNIQUE KEY uk_plugin_signing_key_key_id (key_id)
) ENGINE=InnoDB;
CREATE TABLE platform_plugin (
    id CHAR(26) PRIMARY KEY,
    plugin_key VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    plugin_type VARCHAR(32) NOT NULL,
    active_version VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL,
    signer_key_id CHAR(26) NOT NULL,
    package_checksum CHAR(64) NOT NULL,
    manifest_json JSON NOT NULL,
    permissions JSON NOT NULL,
    entrypoint VARCHAR(180) NOT NULL,
    installed_by CHAR(26) NOT NULL,
    installed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    enabled_at TIMESTAMP(3) NULL,
    disabled_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_platform_plugin_signer FOREIGN KEY (signer_key_id) REFERENCES plugin_signing_key(id),
    UNIQUE KEY uk_platform_plugin_key (plugin_key),
    KEY idx_platform_plugin_status (status, updated_at)
) ENGINE=InnoDB;
CREATE TABLE platform_plugin_release (
    id CHAR(26) PRIMARY KEY,
    plugin_id CHAR(26) NOT NULL,
    version VARCHAR(48) NOT NULL,
    signer_key_id CHAR(26) NOT NULL,
    package_checksum CHAR(64) NOT NULL,
    manifest_json JSON NOT NULL,
    signature_base64 TEXT NOT NULL,
    installed_by CHAR(26) NOT NULL,
    installed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_platform_plugin_release_plugin FOREIGN KEY (plugin_id) REFERENCES platform_plugin(id),
    CONSTRAINT fk_platform_plugin_release_signer FOREIGN KEY (signer_key_id) REFERENCES plugin_signing_key(id),
    UNIQUE KEY uk_platform_plugin_release_version (plugin_id, version),
    KEY idx_platform_plugin_release_time (plugin_id, installed_at)
) ENGINE=InnoDB;
CREATE TABLE enterprise_adapter_configuration (
    id CHAR(26) PRIMARY KEY,
    adapter_type VARCHAR(32) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    endpoint VARCHAR(1024) NOT NULL,
    configuration_ciphertext MEDIUMTEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(26) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_enterprise_adapter_name (adapter_type, display_name),
    KEY idx_enterprise_adapter_status (adapter_type, enabled)
) ENGINE=InnoDB;

-- Source: V48__project_management.sql
ALTER TABLE project ADD COLUMN description VARCHAR(2000) NOT NULL DEFAULT '' AFTER status;
ALTER TABLE project ADD COLUMN created_by CHAR(26) NULL AFTER description;
ALTER TABLE project ADD COLUMN updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE project ADD CONSTRAINT fk_project_creator FOREIGN KEY (created_by) REFERENCES user_account(id);

-- Source: V49__account_management.sql
ALTER TABLE user_account ADD COLUMN account VARCHAR(160) NULL AFTER id;
ALTER TABLE user_account ADD COLUMN phone VARCHAR(40) NULL AFTER display_name;
ALTER TABLE user_account ADD COLUMN deleted_at TIMESTAMP(3) NULL AFTER enabled;
ALTER TABLE user_account MODIFY COLUMN account VARCHAR(160) NOT NULL;
ALTER TABLE user_account ADD UNIQUE KEY uk_user_account (account);
ALTER TABLE user_account ADD KEY idx_user_account_active (deleted_at, enabled);

-- Source: V50__functional_catalog_tab_scopes.sql
ALTER TABLE functional_catalog ADD COLUMN scope VARCHAR(24) NOT NULL DEFAULT 'REQUIREMENTS' AFTER project_id;
ALTER TABLE functional_catalog DROP INDEX uk_functional_catalog_name;
ALTER TABLE functional_catalog DROP INDEX idx_functional_catalog_project;
ALTER TABLE functional_catalog ADD UNIQUE KEY uk_functional_catalog_scope_name (project_id, scope, parent_id, name);
ALTER TABLE functional_catalog ADD KEY idx_functional_catalog_scope (project_id, scope, parent_id, sort_order);

-- Source: V51__functional_case_type.sql
ALTER TABLE functional_case ADD COLUMN case_type VARCHAR(24) NOT NULL DEFAULT 'FUNCTIONAL' AFTER case_code;
ALTER TABLE functional_case ADD KEY idx_functional_case_project_type (project_id, case_type, updated_at);

-- Source: V52__functional_asset_display_ids.sql
ALTER TABLE functional_requirement ADD COLUMN display_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE AFTER id;
ALTER TABLE functional_case ADD COLUMN display_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE AFTER id;

-- Source: V53__functional_defect_zentao_fields.sql
ALTER TABLE functional_defect ADD COLUMN display_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE AFTER id;
ALTER TABLE functional_defect ADD COLUMN catalog_id CHAR(26) NULL AFTER project_id;
ALTER TABLE functional_defect ADD COLUMN priority VARCHAR(8) NOT NULL DEFAULT 'P1' AFTER severity;
ALTER TABLE functional_defect ADD COLUMN confirmed TINYINT(1) NOT NULL DEFAULT 0 AFTER status;
ALTER TABLE functional_defect ADD COLUMN assignee_id CHAR(26) NULL AFTER confirmed;
ALTER TABLE functional_defect ADD COLUMN resolution VARCHAR(24) NULL AFTER assignee_id;
ALTER TABLE functional_defect ADD COLUMN resolved_by CHAR(26) NULL AFTER resolution;
ALTER TABLE functional_defect ADD COLUMN resolved_at TIMESTAMP(3) NULL AFTER resolved_by;
ALTER TABLE functional_defect ADD COLUMN closed_by CHAR(26) NULL AFTER resolved_at;
ALTER TABLE functional_defect ADD COLUMN closed_at TIMESTAMP(3) NULL AFTER closed_by;
ALTER TABLE functional_defect ADD CONSTRAINT fk_functional_defect_catalog FOREIGN KEY (catalog_id) REFERENCES functional_catalog(id);
ALTER TABLE functional_defect ADD CONSTRAINT fk_functional_defect_assignee FOREIGN KEY (assignee_id) REFERENCES user_account(id);
ALTER TABLE functional_defect ADD CONSTRAINT fk_functional_defect_resolved_by FOREIGN KEY (resolved_by) REFERENCES user_account(id);
ALTER TABLE functional_defect ADD CONSTRAINT fk_functional_defect_closed_by FOREIGN KEY (closed_by) REFERENCES user_account(id);
ALTER TABLE functional_defect ADD KEY idx_functional_defect_catalog (project_id, catalog_id, status);
ALTER TABLE functional_defect ADD KEY idx_functional_defect_assignee (project_id, assignee_id, status);

-- Source: V54__functional_defect_assignment_audit.sql
ALTER TABLE functional_defect ADD COLUMN assigned_by CHAR(26) NULL AFTER assignee_id;
ALTER TABLE functional_defect ADD CONSTRAINT fk_functional_defect_assigned_by FOREIGN KEY (assigned_by) REFERENCES user_account(id);

-- Source: V55__functional_defect_submission_metadata.sql
ALTER TABLE functional_defect ADD COLUMN due_date DATE NULL AFTER title;
ALTER TABLE functional_defect ADD COLUMN bug_type VARCHAR(32) NOT NULL DEFAULT 'CODE_ERROR' AFTER due_date;
ALTER TABLE functional_defect ADD COLUMN keywords VARCHAR(500) NOT NULL DEFAULT '' AFTER bug_type;
ALTER TABLE functional_defect ADD COLUMN browsers VARCHAR(255) NOT NULL DEFAULT '' AFTER keywords;
ALTER TABLE functional_defect ADD COLUMN operating_systems VARCHAR(255) NOT NULL DEFAULT '' AFTER browsers;
ALTER TABLE functional_defect ADD COLUMN cc_user_ids VARCHAR(2000) NOT NULL DEFAULT '' AFTER operating_systems;

-- Source: V56__api_definition_cases.sql
CREATE TABLE api_definition_case (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    api_id CHAR(26) NOT NULL,
    name VARCHAR(200) NOT NULL,
    environment_code VARCHAR(64) NULL,
    request_snapshot JSON NOT NULL,
    expected_status SMALLINT UNSIGNED NULL,
    response_validation TEXT NOT NULL,
    saved_response_body MEDIUMTEXT NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_definition_case_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_api_definition_case_api FOREIGN KEY (api_id) REFERENCES api_definition(id) ON DELETE CASCADE,
    UNIQUE KEY uk_api_definition_case_name (api_id, name),
    KEY idx_api_definition_case_project_api (project_id, api_id, updated_at)
) ENGINE=InnoDB;

-- Source: V57__project_versions.sql
CREATE TABLE project_version (
    id CHAR(26) PRIMARY KEY,
    display_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    version_number VARCHAR(100) NOT NULL,
    build_numbers TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'UNSTARTED',
    planned_release_date DATE NULL,
    actual_release_date DATE NULL,
    description TEXT NOT NULL,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_project_version_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_project_version_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_project_version_created_by FOREIGN KEY (created_by) REFERENCES user_account(id),
    CONSTRAINT fk_project_version_updated_by FOREIGN KEY (updated_by) REFERENCES user_account(id),
    UNIQUE KEY uk_project_version_number (project_id, version_number),
    KEY idx_project_version_status_date (project_id, status, planned_release_date)
) ENGINE=InnoDB;

-- Source: V58__api_debug_history_replay_drafts.sql
ALTER TABLE api_debug_history ADD COLUMN protocol VARCHAR(20) NOT NULL DEFAULT 'HTTP' AFTER method;
ALTER TABLE api_debug_history ADD COLUMN replay_draft_ciphertext MEDIUMTEXT NULL AFTER error_message;

-- Source: V59__project_environments.sql
CREATE TABLE project_environment (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(160) NOT NULL,
    environment_code VARCHAR(64) NOT NULL,
    base_url VARCHAR(2000) NOT NULL DEFAULT '',
    description VARCHAR(1200) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 1,
    created_by CHAR(26) NOT NULL,
    updated_by CHAR(26) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_project_environment_project FOREIGN KEY (project_id) REFERENCES project(id),
    UNIQUE KEY uk_project_environment_code (project_id, environment_code),
    KEY idx_project_environment_project_updated (project_id, updated_at)
) ENGINE=InnoDB;

-- Source: V60__expand_api_definition_bodies.sql
ALTER TABLE api_definition_version MODIFY COLUMN request_body MEDIUMTEXT NOT NULL;
ALTER TABLE api_definition_version MODIFY COLUMN response_body MEDIUMTEXT NOT NULL;

-- Source: V61__functional_plan_catalog.sql
ALTER TABLE functional_test_plan ADD COLUMN catalog_id CHAR(26) NULL AFTER project_id;
ALTER TABLE functional_test_plan ADD CONSTRAINT fk_functional_plan_catalog FOREIGN KEY (catalog_id) REFERENCES functional_catalog(id);
ALTER TABLE functional_test_plan ADD KEY idx_functional_plan_catalog (project_id, catalog_id, updated_at);

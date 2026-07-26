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

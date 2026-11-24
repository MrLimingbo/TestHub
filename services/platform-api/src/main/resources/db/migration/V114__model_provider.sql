CREATE TABLE model_provider (
    id VARCHAR(26) NOT NULL PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    api_base_url VARCHAR(2000) NOT NULL,
    api_protocol VARCHAR(32) NOT NULL,
    retry_policy VARCHAR(24) NOT NULL,
    api_key_ciphertext TEXT NULL,
    model_catalog MEDIUMTEXT NOT NULL,
    version BIGINT NOT NULL
);

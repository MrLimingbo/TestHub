ALTER TABLE api_definition_version
    MODIFY COLUMN request_body MEDIUMTEXT NOT NULL,
    MODIFY COLUMN response_body MEDIUMTEXT NOT NULL;

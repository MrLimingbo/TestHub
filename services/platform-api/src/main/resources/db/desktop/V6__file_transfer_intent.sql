CREATE TABLE file_transfer_intent (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    bucket VARCHAR(64) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX idx_file_transfer_intent_created ON file_transfer_intent(created_at);

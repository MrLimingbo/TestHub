ALTER TABLE api_debug_history
    ADD COLUMN protocol VARCHAR(20) NOT NULL DEFAULT 'HTTP' AFTER method,
    ADD COLUMN replay_draft_ciphertext MEDIUMTEXT NULL AFTER error_message;

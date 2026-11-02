-- Serialize account/email identity changes across both login identifier columns.
CREATE TABLE account_identity_guard (
    singleton_id INT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_account_identity_guard CHECK (singleton_id = 1)
);
INSERT INTO account_identity_guard(singleton_id) VALUES (1);

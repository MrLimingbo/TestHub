ALTER TABLE user_account
    ADD COLUMN account VARCHAR(160) NULL AFTER id,
    ADD COLUMN phone VARCHAR(40) NULL AFTER display_name,
    ADD COLUMN deleted_at TIMESTAMP(3) NULL AFTER enabled;

UPDATE user_account
SET account = LOWER(SUBSTRING_INDEX(email, '@', 1))
WHERE account IS NULL;

ALTER TABLE user_account
    MODIFY COLUMN account VARCHAR(160) NOT NULL,
    ADD UNIQUE KEY uk_user_account (account),
    ADD KEY idx_user_account_active (deleted_at, enabled);

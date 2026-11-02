ALTER TABLE api_debug_history ADD COLUMN replay_expires_at DATETIME(3) NULL;
ALTER TABLE api_debug_history ADD COLUMN replay_retention_managed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX api_debug_replay_expiry ON api_debug_history(replay_retention_managed, replay_expires_at);

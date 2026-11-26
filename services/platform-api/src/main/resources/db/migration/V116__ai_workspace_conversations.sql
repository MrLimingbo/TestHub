ALTER TABLE ai_conversation MODIFY COLUMN agent_id CHAR(26) NULL;
ALTER TABLE ai_conversation ADD COLUMN workspace_id CHAR(26);
ALTER TABLE ai_conversation ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_conversation ADD COLUMN custom_title BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_conversation ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ai_conversation ADD CONSTRAINT fk_ai_conversation_workspace FOREIGN KEY (workspace_id) REFERENCES ai_workspace(id);

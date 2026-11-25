CREATE TABLE ai_workspace (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL,
    CONSTRAINT fk_ai_workspace_project FOREIGN KEY (project_id) REFERENCES project(id)
);

CREATE INDEX ix_ai_workspace_scope ON ai_workspace (organization_id, project_id, archived, id);

CREATE TABLE ai_agent (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    workspace_id CHAR(26) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    system_prompt TEXT NOT NULL,
    provider_id CHAR(26) NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    reasoning_level VARCHAR(16),
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL,
    CONSTRAINT fk_ai_agent_workspace FOREIGN KEY (workspace_id) REFERENCES ai_workspace(id)
);

CREATE INDEX ix_ai_agent_scope ON ai_agent (organization_id, project_id, workspace_id, archived, id);

CREATE TABLE ai_conversation (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    agent_id CHAR(26) NOT NULL,
    owner_id CHAR(26) NOT NULL,
    title VARCHAR(120) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ai_conversation_agent FOREIGN KEY (agent_id) REFERENCES ai_agent(id),
    CONSTRAINT fk_ai_conversation_owner FOREIGN KEY (owner_id) REFERENCES user_account(id)
);

CREATE INDEX ix_ai_conversation_scope ON ai_conversation (organization_id, project_id, owner_id, agent_id, updated_at);

CREATE TABLE ai_turn (
    id CHAR(26) PRIMARY KEY,
    organization_id CHAR(26) NOT NULL,
    project_id CHAR(26) NOT NULL,
    conversation_id CHAR(26) NOT NULL,
    turn_number INT NOT NULL,
    attempt_id CHAR(26) NOT NULL,
    provider_id CHAR(26) NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    reasoning_level VARCHAR(16),
    user_text TEXT NOT NULL,
    source_summary VARCHAR(1000) NOT NULL,
    prompt_context CLOB NOT NULL,
    reply CLOB,
    status VARCHAR(16) NOT NULL,
    failure_message VARCHAR(240),
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ai_turn_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT uk_ai_turn_number UNIQUE (conversation_id, turn_number)
);

-- Session tables follow Spring Session JDBC 3.5.6 schema-h2.sql (Apache-2.0).
CREATE TABLE desktop_session (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT desktop_session_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX desktop_session_IX1 ON desktop_session (SESSION_ID);
CREATE INDEX desktop_session_IX2 ON desktop_session (EXPIRY_TIME);
CREATE INDEX desktop_session_IX3 ON desktop_session (PRINCIPAL_NAME);

CREATE TABLE desktop_session_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES LONGVARBINARY NOT NULL,
	CONSTRAINT desktop_session_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT desktop_session_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES desktop_session(PRIMARY_ID) ON DELETE CASCADE
);

-- Installation-scoped ephemeral coordination metadata, not business records.
CREATE TABLE desktop_runtime_state (
    state_key VARCHAR(512) NOT NULL PRIMARY KEY,
    state_value CLOB NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX desktop_runtime_state_expiry ON desktop_runtime_state(expires_at);

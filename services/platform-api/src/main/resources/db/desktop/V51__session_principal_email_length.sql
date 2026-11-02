-- Match the public email limit without changing existing session IDs or attributes.
ALTER TABLE desktop_session ALTER COLUMN PRINCIPAL_NAME VARCHAR(190);

-- Installation-scoped transport metadata; business payloads retain organization/project fields.
CREATE TABLE desktop_stream (
    stream_key VARCHAR(200) PRIMARY KEY,
    last_id BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE desktop_stream_message (
    stream_key VARCHAR(200) NOT NULL,
    message_id BIGINT NOT NULL,
    payload CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (stream_key, message_id),
    FOREIGN KEY (stream_key) REFERENCES desktop_stream(stream_key)
);
CREATE TABLE desktop_stream_group (
    stream_key VARCHAR(200) NOT NULL,
    group_name VARCHAR(200) NOT NULL,
    last_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (stream_key, group_name),
    FOREIGN KEY (stream_key) REFERENCES desktop_stream(stream_key)
);
CREATE TABLE desktop_stream_pending (
    stream_key VARCHAR(200) NOT NULL,
    group_name VARCHAR(200) NOT NULL,
    message_id BIGINT NOT NULL,
    consumer_name VARCHAR(200) NOT NULL,
    delivered_at BIGINT NOT NULL,
    PRIMARY KEY (stream_key, group_name, message_id),
    FOREIGN KEY (stream_key, group_name) REFERENCES desktop_stream_group(stream_key, group_name),
    FOREIGN KEY (stream_key, message_id) REFERENCES desktop_stream_message(stream_key, message_id)
);
CREATE INDEX desktop_pending_idle ON desktop_stream_pending(stream_key,group_name,delivered_at);
CREATE TABLE desktop_runner_heartbeat (
    members_key VARCHAR(200) NOT NULL,
    member_name VARCHAR(200) NOT NULL,
    heartbeat_key VARCHAR(300) NOT NULL UNIQUE,
    body CLOB NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (members_key, member_name)
);

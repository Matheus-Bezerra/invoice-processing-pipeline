CREATE TABLE processed_messages (
    message_id   UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

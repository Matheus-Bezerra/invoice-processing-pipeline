CREATE TABLE outbox (
    id              UUID         PRIMARY KEY,
    invoice_id      UUID         NOT NULL REFERENCES invoices (id),
    access_key      CHAR(44)     NOT NULL,
    payload         JSONB        NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_outbox_next_attempt_at ON outbox (next_attempt_at);

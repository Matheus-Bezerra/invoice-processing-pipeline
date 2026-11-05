CREATE TABLE invoices (
    id               UUID          PRIMARY KEY,
    access_key       CHAR(44)      NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    cnpj             VARCHAR(14),
    amount           NUMERIC(15,2),
    issue_date       DATE,
    cancelled        BOOLEAN,
    approved_at      TIMESTAMPTZ,
    rejection_reason VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_invoices_access_key UNIQUE (access_key),
    CONSTRAINT ck_invoices_status CHECK (status IN ('IN_ANALYSIS', 'APPROVED', 'REJECTED'))
);

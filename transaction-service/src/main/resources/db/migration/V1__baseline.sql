CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS transactions;

GRANT ALL PRIVILEGES ON SCHEMA transactions TO ledger;

CREATE TABLE transactions.transaction (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID            NOT NULL,
    type        VARCHAR(10)     NOT NULL,
    status      VARCHAR(10)     NOT NULL DEFAULT 'POSTED',
    amount      NUMERIC(19,4)   NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS accounts;

GRANT ALL PRIVILEGES ON SCHEMA accounts TO ledger;

CREATE TABLE accounts.account (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name  VARCHAR(255)    NOT NULL,
    status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    balance     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

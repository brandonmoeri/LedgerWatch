-- Schema for account-service
CREATE SCHEMA IF NOT EXISTS accounts;

-- Schema for transaction-service
CREATE SCHEMA IF NOT EXISTS transactions;

GRANT ALL PRIVILEGES ON SCHEMA accounts TO ledger;
GRANT ALL PRIVILEGES ON SCHEMA transactions TO ledger;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS accounts.account (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name  VARCHAR(255)    NOT NULL,
    status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    balance     NUMERIC(19,4)   NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transactions.transaction (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID            NOT NULL,
    type        VARCHAR(10)     NOT NULL,
    status      VARCHAR(10)     NOT NULL DEFAULT 'POSTED',
    amount      NUMERIC(19,4)   NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);
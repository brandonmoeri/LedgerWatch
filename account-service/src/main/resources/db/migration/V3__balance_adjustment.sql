-- One row per balance adjustment applied with a caller-supplied id (transaction-service sends its
-- transaction id). Written in the same transaction as the balance change, so a retried request
-- with the same id is recognized and not applied twice.
CREATE TABLE accounts.balance_adjustment (
    id          UUID            PRIMARY KEY,
    account_id  UUID            NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    delta       NUMERIC(19,4)   NOT NULL,
    applied_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

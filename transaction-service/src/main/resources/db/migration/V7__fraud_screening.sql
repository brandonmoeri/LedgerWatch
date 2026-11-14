-- One row per transaction the fraud rules have screened, written before its balance change is
-- sent. A redelivered outbox event reuses the stored verdict instead of re-running the rules, whose
-- inputs (e.g. the 30-day average) may have moved since: a transaction must not be posted on one
-- attempt and rejected on the next. Like outbox_event, it has no foreign key to transaction.
CREATE TABLE transactions.fraud_screening (
    transaction_id UUID        PRIMARY KEY,
    blocked        BOOLEAN     NOT NULL,             -- true: rejected as SUSPECTED_FRAUD
    signals        JSONB       NOT NULL,             -- [{"rule": "...", "detail": "..."}], may be []
    screened_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Flagged transactions only, for review queues.
CREATE INDEX fraud_screening_flagged_idx
    ON transactions.fraud_screening (screened_at)
    WHERE signals <> '[]'::jsonb;

-- Velocity and 30-day-average lookups scan one account's transactions by creation time.
CREATE INDEX transaction_account_created_at_idx
    ON transactions.transaction (account_id, created_at);

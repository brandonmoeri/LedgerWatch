-- One row per Idempotency-Key a caller has used on POST /transactions. The unique constraint is
-- what serializes concurrent retries: the second INSERT ... ON CONFLICT waits for the first
-- request's transaction, then either replays its stored response or, if it rolled back, proceeds.
CREATE TABLE transactions.idempotency_record (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    caller          VARCHAR(255)    NOT NULL,
    idempotency_key VARCHAR(255)    NOT NULL,
    request_hash    VARCHAR(64)     NOT NULL,
    transaction_id  UUID            REFERENCES transactions.transaction (id) ON DELETE CASCADE,
    response_body   TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_record_caller_key UNIQUE (caller, idempotency_key)
);

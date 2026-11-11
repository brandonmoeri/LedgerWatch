-- One row per fraud rule that fired on a transaction, keyed by account so a reviewer can list an
-- account's flags (GET /accounts/{id}/flags) without unpacking fraud_screening.signals. Written in
-- the same DB transaction as the transaction's fraud_screening row, and never changed afterwards.
--
-- fraud_screening.signals stays: it is the verdict a redelivered outbox event replays, and
-- instances still running the previous release write and read it during a rolling deploy.
CREATE TABLE transactions.fraud_flag (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID         NOT NULL
                   REFERENCES transactions.fraud_screening (transaction_id) ON DELETE CASCADE,
    account_id     UUID         NOT NULL,
    rule           VARCHAR(64)  NOT NULL,   -- FraudRule.name(), e.g. VELOCITY
    detail         TEXT         NOT NULL,
    blocked        BOOLEAN      NOT NULL,   -- the screening's verdict: rejected (ENFORCE) or posted anyway (MONITOR)
    flagged_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fraud_flag_transaction_rule_uq UNIQUE (transaction_id, rule)
);

-- An account's flags, newest first.
CREATE INDEX fraud_flag_account_flagged_at_idx
    ON transactions.fraud_flag (account_id, flagged_at DESC, id);

-- Flags for the screenings stored before this table existed.
INSERT INTO transactions.fraud_flag (transaction_id, account_id, rule, detail, blocked, flagged_at)
SELECT s.transaction_id, t.account_id, signal ->> 'rule', signal ->> 'detail', s.blocked, s.screened_at
FROM transactions.fraud_screening s
JOIN transactions.transaction t ON t.id = s.transaction_id
CROSS JOIN LATERAL jsonb_array_elements(s.signals) AS signal
ON CONFLICT (transaction_id, rule) DO NOTHING;

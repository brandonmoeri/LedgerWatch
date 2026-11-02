-- Delivery state for the outbox publisher. A pending row is due once next_attempt_at has passed;
-- the publisher pushes it forward both to lease a claimed row (so a crashed instance's batch is
-- picked up again later) and to back off after a transient failure. attempts counts failed
-- deliveries. failed_at parks a row the consumer rejected outright; it is never retried.
ALTER TABLE transactions.outbox_event
    ADD COLUMN attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_error      TEXT,
    ADD COLUMN failed_at       TIMESTAMPTZ;

UPDATE transactions.outbox_event SET next_attempt_at = created_at;

-- The publisher's work queue: pending rows only, soonest due first.
DROP INDEX transactions.ix_outbox_event_unpublished;
CREATE INDEX ix_outbox_event_pending
    ON transactions.outbox_event (next_attempt_at, created_at)
    WHERE published_at IS NULL AND failed_at IS NULL;

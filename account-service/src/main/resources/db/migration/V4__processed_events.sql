-- One row per consumed event (transaction-service's outbox event id). Written in the same
-- transaction as the event's balance change, so a redelivered event is recognized and skipped.
-- Kept separate from balance_adjustment: that dedupes the change itself (keyed by transaction id,
-- which the synchronous call also uses); this dedupes delivery of the event.
CREATE TABLE accounts.processed_events (
    event_id      UUID            PRIMARY KEY,
    event_type    VARCHAR(64)     NOT NULL,
    processed_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

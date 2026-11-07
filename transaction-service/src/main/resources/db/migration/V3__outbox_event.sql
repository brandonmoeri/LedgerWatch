-- Transactional outbox. Each state change is written here in the same local DB transaction as the
-- change itself, so an event exists if and only if the change committed. A relay reads unpublished
-- rows in insertion order, publishes them, and sets published_at. Delivery is at-least-once, so
-- consumers de-duplicate on id.
CREATE TABLE transactions.outbox_event (
    id             UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64)     NOT NULL,
    aggregate_id   UUID            NOT NULL,
    event_type     VARCHAR(64)     NOT NULL,
    payload        JSONB           NOT NULL,
    created_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- The relay's work queue: only unpublished rows, oldest first.
CREATE INDEX ix_outbox_event_unpublished
    ON transactions.outbox_event (created_at)
    WHERE published_at IS NULL;

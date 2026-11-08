-- Correlation id of the request that wrote the event. The publisher delivers it later on a
-- scheduler thread, so the id is carried here to tie its logs, and account-service's, back to that
-- request. Null for rows written before this column existed; the publisher mints one for those.
ALTER TABLE transactions.outbox_event
    ADD COLUMN correlation_id VARCHAR(128);

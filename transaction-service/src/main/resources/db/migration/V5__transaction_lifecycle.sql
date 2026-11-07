-- Transactions now start PENDING and are settled by the outbox publisher. Existing rows keep their
-- status: their balance change was applied synchronously when they were created.
ALTER TABLE transactions.transaction
    ALTER COLUMN status SET DEFAULT 'PENDING',
    ADD COLUMN rejection_reason VARCHAR(32);

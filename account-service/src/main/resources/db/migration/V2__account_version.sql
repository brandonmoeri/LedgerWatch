-- Optimistic-locking counter for JPA @Version on Account.
ALTER TABLE accounts.account ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

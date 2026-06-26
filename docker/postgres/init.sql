-- Schema for account-service
CREATE SCHEMA IF NOT EXISTS accounts;

-- Schema for transaction-service
CREATE SCHEMA IF NOT EXISTS transactions;

GRANT ALL PRIVILEGES ON SCHEMA accounts TO ledger;
GRANT ALL PRIVILEGES ON SCHEMA transactions TO ledger;
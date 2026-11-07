package com.ledgerwatch.transactionservice.domain;

/** Why account-service refused to post a transaction. */
public enum TransactionRejectionReason {
  ACCOUNT_NOT_FOUND,
  ACCOUNT_NOT_ACTIVE,
  INSUFFICIENT_FUNDS
}

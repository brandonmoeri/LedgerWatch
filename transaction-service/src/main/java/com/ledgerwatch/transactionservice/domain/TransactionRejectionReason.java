package com.ledgerwatch.transactionservice.domain;

/** Why a transaction was refused: by account-service, or by the fraud rules before it was sent. */
public enum TransactionRejectionReason {
  ACCOUNT_NOT_FOUND,
  ACCOUNT_NOT_ACTIVE,
  INSUFFICIENT_FUNDS,
  /** One or more fraud rules fired; the balance was not touched. See {@code fraud_alert}. */
  SUSPECTED_FRAUD
}

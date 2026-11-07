package com.ledgerwatch.transactionservice.domain;

/**
 * {@code PENDING -> POSTED | REJECTED} is decided by account-service's answer to the outbox event;
 * {@code POSTED -> VOIDED} is an admin action. REJECTED and VOIDED are terminal.
 */
public enum TransactionStatus {
  PENDING,
  POSTED,
  REJECTED,
  VOIDED
}

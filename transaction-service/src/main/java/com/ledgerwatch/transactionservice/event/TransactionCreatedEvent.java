package com.ledgerwatch.transactionservice.event;

import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code TransactionCreated} outbox event. */
public record TransactionCreatedEvent(
    UUID transactionId,
    UUID accountId,
    TransactionType type,
    BigDecimal amount,
    String description,
    Instant createdAt) {
  public static final String AGGREGATE_TYPE = "Transaction";
  public static final String EVENT_TYPE = "TransactionCreated";

  /** The signed change to the account balance: {@code +amount} for CREDIT, {@code -amount} else. */
  public BigDecimal balanceDelta() {
    return type == TransactionType.CREDIT ? amount : amount.negate();
  }

  public static TransactionCreatedEvent from(Transaction t) {
    return new TransactionCreatedEvent(
        t.getId(),
        t.getAccountId(),
        t.getType(),
        t.getAmount(),
        t.getDescription(),
        t.getCreatedAt());
  }
}

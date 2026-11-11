package com.ledgerwatch.transactionservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One fraud rule that fired on a transaction. Written once, through {@code
 * FraudFlagRepository.record}, alongside the transaction's {@link FraudScreening}, and never
 * changed.
 */
@Entity
@Immutable
@Table(name = "fraud_flag")
public class FraudFlag {

  @Id private UUID id;

  @Column(name = "transaction_id", nullable = false)
  private UUID transactionId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(nullable = false, length = 64)
  private String rule;

  @Column(nullable = false, columnDefinition = "text")
  private String detail;

  /** Whether the screening rejected the transaction, rather than only flagging it (MONITOR). */
  @Column(nullable = false)
  private boolean blocked;

  /** The screening's time: the same instant as its {@code fraud_screening.screened_at}. */
  @Column(name = "flagged_at", nullable = false)
  private Instant flaggedAt;

  protected FraudFlag() {}

  public UUID getId() {
    return id;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getRule() {
    return rule;
  }

  public String getDetail() {
    return detail;
  }

  public boolean isBlocked() {
    return blocked;
  }

  public Instant getFlaggedAt() {
    return flaggedAt;
  }
}

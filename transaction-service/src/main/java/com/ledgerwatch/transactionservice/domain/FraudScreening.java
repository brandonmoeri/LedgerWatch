package com.ledgerwatch.transactionservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The fraud rules' verdict on one transaction. Written once, through {@code
 * FraudScreeningRepository.record}, and read back when the transaction's event is redelivered.
 */
@Entity
@Table(name = "fraud_screening")
public class FraudScreening {

  @Id
  @Column(name = "transaction_id")
  private UUID transactionId;

  @Column(nullable = false)
  private boolean blocked;

  /** JSON array of {@code {"rule", "detail"}} objects; {@code []} when no rule fired. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String signals;

  @Column(name = "screened_at", nullable = false, updatable = false)
  private Instant screenedAt;

  protected FraudScreening() {}

  public UUID getTransactionId() {
    return transactionId;
  }

  public boolean isBlocked() {
    return blocked;
  }

  public String getSignals() {
    return signals;
  }

  public Instant getScreenedAt() {
    return screenedAt;
  }
}

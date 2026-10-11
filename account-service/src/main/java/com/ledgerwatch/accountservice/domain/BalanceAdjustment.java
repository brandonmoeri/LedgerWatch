package com.ledgerwatch.accountservice.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A balance adjustment applied under a caller-supplied id. Rows are insert-only, so the entity
 * always reports itself as new and {@code save} persists without a merge-time SELECT.
 */
@Entity
@Table(name = "balance_adjustment")
public class BalanceAdjustment implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Column(nullable = false, updatable = false, precision = 19, scale = 4)
  private BigDecimal delta;

  @Column(name = "applied_at", nullable = false, updatable = false)
  private Instant appliedAt = Instant.now();

  protected BalanceAdjustment() {}

  public BalanceAdjustment(UUID id, UUID accountId, BigDecimal delta) {
    this.id = id;
    this.accountId = accountId;
    this.delta = delta;
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return true;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public BigDecimal getDelta() {
    return delta;
  }

  public Instant getAppliedAt() {
    return appliedAt;
  }
}

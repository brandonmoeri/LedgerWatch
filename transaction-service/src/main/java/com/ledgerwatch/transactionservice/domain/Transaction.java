package com.ledgerwatch.transactionservice.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Starts PENDING and is settled to POSTED or REJECTED by the outbox publisher. Updates write only
 * changed columns, so a PATCH of the description can't overwrite a status settled while it ran.
 */
@Entity
@DynamicUpdate
public class Transaction {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private TransactionType type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private TransactionStatus status = TransactionStatus.PENDING;

  @Enumerated(EnumType.STRING)
  @Column(name = "rejection_reason", length = 32)
  private TransactionRejectionReason rejectionReason;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(length = 255)
  private String description;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public void setAccountId(UUID accountId) {
    this.accountId = accountId;
  }

  public TransactionType getType() {
    return type;
  }

  public void setType(TransactionType type) {
    this.type = type;
  }

  public TransactionStatus getStatus() {
    return status;
  }

  public void setStatus(TransactionStatus status) {
    this.status = status;
  }

  public TransactionRejectionReason getRejectionReason() {
    return rejectionReason;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

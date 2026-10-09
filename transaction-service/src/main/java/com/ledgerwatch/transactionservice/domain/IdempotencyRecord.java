package com.ledgerwatch.transactionservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A caller's use of an {@code Idempotency-Key} on {@code POST /transactions}. Rows are written with
 * native/bulk queries in {@code IdempotencyRecordRepository}; the entity is only read.
 */
@Entity
public class IdempotencyRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String caller;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Column(name = "transaction_id")
  private UUID transactionId;

  @Column(name = "response_body", columnDefinition = "text")
  private String responseBody;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  public UUID getId() {
    return id;
  }

  public String getCaller() {
    return caller;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

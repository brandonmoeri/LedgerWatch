package com.ledgerwatch.transactionservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An event waiting to be published, written in the same DB transaction as the change it describes.
 * The id is the event id consumers de-duplicate on; {@code publishedAt} stays null until a relay
 * has delivered it.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "aggregate_type", nullable = false, length = 64)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt = createdAt;

  @Column(name = "last_error", columnDefinition = "text")
  private String lastError;

  @Column(name = "failed_at")
  private Instant failedAt;

  /** Correlation id of the request that wrote the event, carried across to its delivery. */
  @Column(name = "correlation_id", updatable = false, length = 128)
  private String correlationId;

  protected OutboxEvent() {}

  public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload) {
    this(aggregateType, aggregateId, eventType, payload, null);
  }

  public OutboxEvent(
      String aggregateType,
      UUID aggregateId,
      String eventType,
      String payload,
      String correlationId) {
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.correlationId = correlationId;
  }

  /** Holds the event until {@code until}, so other publishers skip it while it's being sent. */
  public void lease(Instant until) {
    this.nextAttemptAt = until;
  }

  public void markPublished(Instant at) {
    this.publishedAt = at;
    this.lastError = null;
  }

  /** Records a transient failure and makes the event due again at {@code retryAt}. */
  public void scheduleRetry(Instant retryAt, String error) {
    this.attempts++;
    this.nextAttemptAt = retryAt;
    this.lastError = error;
  }

  /** Parks the event: the consumer rejected it, so it is never retried automatically. */
  public void markFailed(Instant at, String error) {
    this.attempts++;
    this.failedAt = at;
    this.lastError = error;
  }

  public UUID getId() {
    return id;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getFailedAt() {
    return failedAt;
  }

  public String getCorrelationId() {
    return correlationId;
  }
}

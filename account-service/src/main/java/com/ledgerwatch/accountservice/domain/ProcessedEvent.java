package com.ledgerwatch.accountservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * An event this service has consumed. Rows are insert-only, so the entity always reports itself as
 * new and {@code save} persists without a merge-time SELECT.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "event_type", nullable = false, updatable = false, length = 64)
  private String eventType;

  @Column(name = "processed_at", nullable = false, updatable = false)
  private Instant processedAt = Instant.now();

  protected ProcessedEvent() {}

  public ProcessedEvent(UUID eventId, String eventType) {
    this.eventId = eventId;
    this.eventType = eventType;
  }

  @Override
  public UUID getId() {
    return eventId;
  }

  @Override
  public boolean isNew() {
    return true;
  }

  public String getEventType() {
    return eventType;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}

package com.ledgerwatch.transactionservice.metrics;

import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Gauges over the outbox's undelivered events, the ones neither published nor parked:
 *
 * <ul>
 *   <li>{@code ledgerwatch.outbox.lag}: age of the oldest undelivered event, or 0 when there are
 *       none. This is how far behind the relay is. It keeps growing while account-service is down
 *       or the publisher has stalled, however few events are waiting.
 *   <li>{@code ledgerwatch.outbox.pending}: how many events are undelivered.
 * </ul>
 *
 * <p>Events backing off after a transient failure count; parked ones don't, since they will never
 * be delivered without an operator. Both gauges query the database each time a registry samples
 * them (once per step for CloudWatch), so every instance reports the same shared backlog.
 */
@Component
public class OutboxBacklogMetrics implements MeterBinder {

  public static final String LAG = "ledgerwatch.outbox.lag";
  public static final String PENDING = "ledgerwatch.outbox.pending";

  private final OutboxEventRepository repo;
  private final Clock clock;

  @Autowired
  public OutboxBacklogMetrics(OutboxEventRepository repo) {
    this(repo, Clock.systemUTC());
  }

  OutboxBacklogMetrics(OutboxEventRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    TimeGauge.builder(LAG, this, TimeUnit.MILLISECONDS, OutboxBacklogMetrics::lagMillis)
        .description("Age of the oldest outbox event not yet delivered to account-service")
        .register(registry);
    Gauge.builder(PENDING, this, OutboxBacklogMetrics::pending)
        .description("Outbox events not yet delivered to account-service")
        .baseUnit("events")
        .register(registry);
  }

  double lagMillis() {
    Instant oldest = repo.findOldestUndeliveredCreatedAt();
    return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toMillis());
  }

  double pending() {
    return repo.countByPublishedAtIsNullAndFailedAtIsNull();
  }
}

package com.ledgerwatch.transactionservice.config;

import com.ledgerwatch.transactionservice.event.OutboxPublisher;
import com.ledgerwatch.transactionservice.event.OutboxPublisherProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Schedules the outbox publisher's poll every {@code outbox.publisher.poll-interval}. Off when
 * {@code outbox.publisher.enabled=false}, as in tests, which call the publisher directly.
 *
 * <p>The poll is registered here rather than with {@code @Scheduled(fixedDelayString = ...)}
 * because that attribute only accepts milliseconds or ISO-8601 ({@code PT1S}), while the property
 * is bound as a {@link java.time.Duration} and so also accepts the {@code 1s} form used in config.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
public class SchedulingConfig implements SchedulingConfigurer {

  private final OutboxPublisher outboxPublisher;
  private final OutboxPublisherProperties outboxProperties;

  public SchedulingConfig(
      OutboxPublisher outboxPublisher, OutboxPublisherProperties outboxProperties) {
    this.outboxPublisher = outboxPublisher;
    this.outboxProperties = outboxProperties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(outboxPublisher::publishPending, outboxProperties.pollInterval());
  }
}

package com.ledgerwatch.transactionservice.event;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for {@link OutboxPublisher} ({@code outbox.publisher.*}).
 *
 * @param enabled whether the scheduled poll runs at all
 * @param pollInterval delay between the end of one poll and the start of the next
 * @param batchSize maximum events claimed per poll
 * @param lease how long a claimed event is hidden from other publishers; should cover delivering a
 *     whole batch, or a slow batch may be delivered twice (harmless, since delivery is idempotent)
 * @param initialBackoff wait after the first transient failure; doubles with each further one
 * @param maxBackoff cap on that wait
 */
@Validated
@ConfigurationProperties("outbox.publisher")
public record OutboxPublisherProperties(
    boolean enabled,
    @NotNull Duration pollInterval,
    @Positive int batchSize,
    @NotNull Duration lease,
    @NotNull Duration initialBackoff,
    @NotNull Duration maxBackoff) {}

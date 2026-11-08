package com.ledgerwatch.transactionservice.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/** The outbox gauges, read from the Boot-managed registry against real Postgres. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class OutboxBacklogMetricsIntegrationTest {

  @Autowired MeterRegistry meterRegistry;
  @Autowired OutboxEventRepository outboxEventRepository;

  @BeforeEach
  @AfterEach
  void cleanUp() {
    outboxEventRepository.deleteAll();
  }

  private void eventCreated(Duration ago, Consumer<OutboxEvent> state) {
    OutboxEvent event =
        new OutboxEvent("Transaction", UUID.randomUUID(), "TransactionCreated", "{}");
    ReflectionTestUtils.setField(event, "createdAt", Instant.now().minus(ago));
    state.accept(event);
    outboxEventRepository.save(event);
  }

  private double lagSeconds() {
    return meterRegistry.get(OutboxBacklogMetrics.LAG).timeGauge().value(TimeUnit.SECONDS);
  }

  private double pending() {
    return meterRegistry.get(OutboxBacklogMetrics.PENDING).gauge().value();
  }

  @Test
  void emptyOutbox_hasNoLag() {
    assertThat(lagSeconds()).isZero();
    assertThat(pending()).isZero();
  }

  @Test
  void lag_isTheAgeOfTheOldestUndeliveredEvent_includingOnesBackingOff() {
    eventCreated(Duration.ofSeconds(30), e -> {});
    eventCreated(
        Duration.ofSeconds(90),
        e -> e.scheduleRetry(Instant.now().plusSeconds(60), "account-service returned 503"));

    assertThat(pending()).isEqualTo(2);
    assertThat(lagSeconds()).isCloseTo(90, within(5.0));
  }

  @Test
  void publishedAndParkedEvents_areNotBacklog() {
    eventCreated(Duration.ofMinutes(10), e -> e.markPublished(Instant.now()));
    eventCreated(Duration.ofMinutes(5), e -> e.markFailed(Instant.now(), "rejected"));
    eventCreated(Duration.ofSeconds(20), e -> {});

    assertThat(pending()).isEqualTo(1);
    assertThat(lagSeconds()).isCloseTo(20, within(5.0));
  }
}

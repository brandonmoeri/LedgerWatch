package com.ledgerwatch.transactionservice.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots with the publisher enabled, which the test profile otherwise turns off, and the poll
 * interval in the {@code 1h}-style form application.yml uses. A {@code @Scheduled} string rejected
 * that form and kept the service from starting.
 */
@SpringBootTest(properties = {"outbox.publisher.enabled=true", "outbox.publisher.poll-interval=1h"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxPublisherSchedulingIntegrationTest {

  @Autowired ScheduledTaskHolder scheduledTasks;

  @Test
  void schedulesPollAtConfiguredInterval() {
    assertThat(scheduledTasks.getScheduledTasks())
        .map(scheduled -> scheduled.getTask())
        .filteredOn(FixedDelayTask.class::isInstance)
        .map(task -> ((FixedDelayTask) task).getIntervalDuration())
        .containsExactly(Duration.ofHours(1));
  }
}

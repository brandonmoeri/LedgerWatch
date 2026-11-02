package com.ledgerwatch.transactionservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.client.AccountServiceUnavailableException;
import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Relays pending outbox events to account-service.
 *
 * <p>Each poll claims a batch of due events in a short transaction ({@code FOR UPDATE SKIP LOCKED},
 * then a lease pushes {@code next_attempt_at} forward), and delivers them one at a time outside any
 * DB transaction. If an instance dies mid-batch, its leases expire and the events are claimed
 * again. Delivery is therefore at-least-once; account-service de-duplicates on the adjustment id.
 *
 * <p>Outcomes per event: delivered → {@code published_at} set; account-service unavailable (I/O,
 * timeout, 5xx after the client's own retries) → exponential backoff; any other rejection → parked
 * with {@code failed_at}. While the {@code accountService} circuit breaker is open, polls are
 * skipped, and events claimed when it trips are released untouched.
 */
@Component
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  static final int MAX_ERROR_LENGTH = 1000;

  private final OutboxEventRepository repo;
  private final AccountServiceClient accountServiceClient;
  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final TransactionOperations transactions;
  private final ObjectMapper objectMapper;
  private final OutboxPublisherProperties properties;
  private final Clock clock;

  @Autowired
  public OutboxPublisher(
      OutboxEventRepository repo,
      AccountServiceClient accountServiceClient,
      CircuitBreakerRegistry circuitBreakerRegistry,
      TransactionOperations transactions,
      ObjectMapper objectMapper,
      OutboxPublisherProperties properties) {
    this(
        repo,
        accountServiceClient,
        circuitBreakerRegistry,
        transactions,
        objectMapper,
        properties,
        Clock.systemUTC());
  }

  OutboxPublisher(
      OutboxEventRepository repo,
      AccountServiceClient accountServiceClient,
      CircuitBreakerRegistry circuitBreakerRegistry,
      TransactionOperations transactions,
      ObjectMapper objectMapper,
      OutboxPublisherProperties properties,
      Clock clock) {
    this.repo = repo;
    this.accountServiceClient = accountServiceClient;
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.transactions = transactions;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.clock = clock;
  }

  /** One poll. Scheduled only when {@code outbox.publisher.enabled} (see SchedulingConfig). */
  @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval}")
  public void publishPending() {
    if (breaker().getState() == CircuitBreaker.State.OPEN) {
      log.debug("Skipping outbox poll: account-service circuit breaker is open");
      return;
    }
    List<OutboxEvent> batch = claim();
    for (int i = 0; i < batch.size(); i++) {
      if (!publish(batch.get(i))) {
        release(batch.subList(i, batch.size()));
        return;
      }
    }
  }

  private CircuitBreaker breaker() {
    return circuitBreakerRegistry.circuitBreaker(AccountServiceClient.RESILIENCE_INSTANCE);
  }

  private List<OutboxEvent> claim() {
    Instant now = clock.instant();
    return transactions.execute(
        status -> {
          List<OutboxEvent> due = repo.lockDue(now, properties.batchSize());
          Instant leaseUntil = now.plus(properties.lease());
          due.forEach(event -> event.lease(leaseUntil));
          return due;
        });
  }

  /**
   * Delivers one event and stores the outcome. Returns false, storing nothing, if the circuit
   * breaker refused the call.
   */
  private boolean publish(OutboxEvent event) {
    try {
      deliver(event);
      event.markPublished(clock.instant());
    } catch (CallNotPermittedException e) {
      return false;
    } catch (AccountServiceUnavailableException e) {
      Duration wait = backoff(event.getAttempts() + 1);
      event.scheduleRetry(clock.instant().plus(wait), errorMessage(e));
      log.warn(
          "Outbox event {} not delivered (attempt {}), retrying in {}: {}",
          event.getId(),
          event.getAttempts(),
          wait,
          e.getMessage());
    } catch (RuntimeException e) {
      event.markFailed(clock.instant(), errorMessage(e));
      log.error("Outbox event {} rejected, parking it: {}", event.getId(), e.getMessage(), e);
    }
    repo.save(event);
    return true;
  }

  /**
   * A {@code TransactionCreated} event becomes the same balance adjustment {@code POST
   * /transactions} already made, keyed by the transaction id, so a replay of an applied adjustment
   * is a no-op on account-service's side.
   */
  private void deliver(OutboxEvent event) {
    if (!TransactionCreatedEvent.EVENT_TYPE.equals(event.getEventType())) {
      throw new IllegalArgumentException("No delivery for event type " + event.getEventType());
    }
    TransactionCreatedEvent payload;
    try {
      payload = objectMapper.readValue(event.getPayload(), TransactionCreatedEvent.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unreadable payload: " + e.getOriginalMessage(), e);
    }
    accountServiceClient.applyBalanceDelta(
        payload.accountId(), payload.balanceDelta(), payload.transactionId());
  }

  private void release(List<OutboxEvent> events) {
    Instant now = clock.instant();
    events.forEach(event -> event.lease(now));
    repo.saveAll(events);
  }

  /** {@code initialBackoff * 2^(failures - 1)}, capped at {@code maxBackoff}. */
  Duration backoff(int failures) {
    Duration wait = properties.initialBackoff().multipliedBy(1L << Math.min(failures - 1, 30));
    return wait.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : wait;
  }

  private static String errorMessage(RuntimeException e) {
    String message = e.getClass().getSimpleName() + ": " + e.getMessage();
    return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
  }
}

package com.ledgerwatch.transactionservice.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.common.error.AccountNotActiveException;
import com.ledgerwatch.common.error.InsufficientFundsException;
import com.ledgerwatch.common.logging.CorrelationId;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.client.AccountServiceConflictException;
import com.ledgerwatch.transactionservice.client.AccountServiceUnavailableException;
import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import com.ledgerwatch.transactionservice.domain.TransactionRejectionReason;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.fraud.FraudContext;
import com.ledgerwatch.transactionservice.fraud.FraudRuleEngine;
import com.ledgerwatch.transactionservice.fraud.FraudVerdict;
import com.ledgerwatch.transactionservice.fraud.SuspectedFraudException;
import com.ledgerwatch.transactionservice.metrics.TransactionMetrics;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Relays pending outbox events to account-service, which is what posts a transaction: this is the
 * only place its balance is applied, and the answer settles its status.
 *
 * <p>Each poll claims a batch of due events in a short transaction ({@code FOR UPDATE SKIP LOCKED},
 * then a lease pushes {@code next_attempt_at} forward), and delivers them one at a time outside any
 * DB transaction. If an instance dies mid-batch, its leases expire and the events are claimed
 * again. Delivery is therefore at-least-once; account-service de-duplicates on the adjustment id.
 *
 * <p>Outcomes per event:
 *
 * <ul>
 *   <li>Delivered → {@code published_at} set, transaction POSTED.
 *   <li>Business rejection (no such account, account not active, insufficient funds) → parked with
 *       {@code failed_at}, transaction REJECTED. These answers are final.
 *   <li>Transient failure (I/O, timeout or 5xx after the client's own retries, or a 409 that isn't
 *       a business rejection) → exponential backoff, transaction stays PENDING.
 *   <li>Anything else (auth failure, unexpected 4xx, unusable event) → parked, transaction stays
 *       PENDING. This says nothing about the transaction, and the balance was not changed, so it
 *       needs an operator rather than a verdict.
 * </ul>
 *
 * <p>The event and its transaction are updated in one DB transaction. If an instance dies after
 * delivering but before that commits, redelivery replays the adjustment and settles it then. While
 * the {@code accountService} circuit breaker is open, polls are skipped, and events claimed when it
 * trips are released untouched.
 *
 * <p>{@link TransactionMetrics} counts a transaction as posted or rejected once that update has
 * committed, and only if it moved the transaction out of PENDING, so a redelivery doesn't count it
 * twice.
 */
@Component
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  static final int MAX_ERROR_LENGTH = 1000;
  static final String MDC_EVENT_ID = "outboxEventId";
  static final String MDC_TRANSACTION_ID = "transactionId";

  private final OutboxEventRepository repo;
  private final TransactionRepository transactionRepo;
  private final AccountServiceClient accountServiceClient;
  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final TransactionOperations transactions;
  private final ObjectMapper objectMapper;
  private final OutboxPublisherProperties properties;
  private final TransactionMetrics metrics;
  private final FraudRuleEngine fraudRules;
  private final Clock clock;

  @Autowired
  public OutboxPublisher(
      OutboxEventRepository repo,
      TransactionRepository transactionRepo,
      AccountServiceClient accountServiceClient,
      CircuitBreakerRegistry circuitBreakerRegistry,
      TransactionOperations transactions,
      ObjectMapper objectMapper,
      OutboxPublisherProperties properties,
      TransactionMetrics metrics,
      FraudRuleEngine fraudRules) {
    this(
        repo,
        transactionRepo,
        accountServiceClient,
        circuitBreakerRegistry,
        transactions,
        objectMapper,
        properties,
        metrics,
        fraudRules,
        Clock.systemUTC());
  }

  OutboxPublisher(
      OutboxEventRepository repo,
      TransactionRepository transactionRepo,
      AccountServiceClient accountServiceClient,
      CircuitBreakerRegistry circuitBreakerRegistry,
      TransactionOperations transactions,
      ObjectMapper objectMapper,
      OutboxPublisherProperties properties,
      TransactionMetrics metrics,
      FraudRuleEngine fraudRules,
      Clock clock) {
    this.repo = repo;
    this.transactionRepo = transactionRepo;
    this.accountServiceClient = accountServiceClient;
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.transactions = transactions;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.metrics = metrics;
    this.fraudRules = fraudRules;
    this.clock = clock;
  }

  /** One poll. Scheduled every {@code outbox.publisher.poll-interval} by SchedulingConfig. */
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
   *
   * <p>Runs under the correlation id of the request that wrote the event, so this delivery's logs,
   * and account-service's (the id is forwarded on the call), trace back to that request.
   */
  private boolean publish(OutboxEvent event) {
    try (CorrelationId.Scope scope = CorrelationId.open(event.getCorrelationId());
        MDC.MDCCloseable eventId = MDC.putCloseable(MDC_EVENT_ID, String.valueOf(event.getId()));
        MDC.MDCCloseable txId =
            MDC.putCloseable(MDC_TRANSACTION_ID, String.valueOf(event.getAggregateId()))) {
      return deliverAndRecord(event);
    }
  }

  private boolean deliverAndRecord(OutboxEvent event) {
    TransactionType type;
    try {
      type = deliver(event);
    } catch (CallNotPermittedException e) {
      return false;
    } catch (AccountServiceUnavailableException | AccountServiceConflictException e) {
      scheduleRetry(event, e);
      return true;
    } catch (SuspectedFraudException e) {
      reject(event, TransactionRejectionReason.SUSPECTED_FRAUD, e);
      return true;
    } catch (NoSuchElementException e) {
      reject(event, TransactionRejectionReason.ACCOUNT_NOT_FOUND, e);
      return true;
    } catch (AccountNotActiveException e) {
      reject(event, TransactionRejectionReason.ACCOUNT_NOT_ACTIVE, e);
      return true;
    } catch (InsufficientFundsException e) {
      reject(event, TransactionRejectionReason.INSUFFICIENT_FUNDS, e);
      return true;
    } catch (RuntimeException e) {
      event.markFailed(clock.instant(), errorMessage(e));
      log.error("Outbox event {} undeliverable, parking it: {}", event.getId(), e.getMessage(), e);
      repo.save(event);
      return true;
    }
    Instant now = clock.instant();
    event.markPublished(now);
    if (settle(event, () -> transactionRepo.markPosted(event.getAggregateId(), now))) {
      metrics.posted(type);
    }
    return true;
  }

  private void scheduleRetry(OutboxEvent event, RuntimeException e) {
    Duration wait = backoff(event.getAttempts() + 1);
    event.scheduleRetry(clock.instant().plus(wait), errorMessage(e));
    log.warn(
        "Outbox event {} not delivered (attempt {}), retrying in {}: {}",
        event.getId(),
        event.getAttempts(),
        wait,
        e.getMessage());
    repo.save(event);
  }

  private void reject(OutboxEvent event, TransactionRejectionReason reason, RuntimeException e) {
    Instant now = clock.instant();
    event.markFailed(now, errorMessage(e));
    log.info("Transaction {} rejected ({}): {}", event.getAggregateId(), reason, e.getMessage());
    if (settle(event, () -> transactionRepo.markRejected(event.getAggregateId(), reason, now))) {
      metrics.rejected(reason);
    }
  }

  /**
   * Saves the event and runs {@code markTransaction} in one DB transaction. Returns true once that
   * has committed if it changed the transaction's status, and false if the transaction had already
   * left PENDING (a redelivery, or an admin got there first).
   */
  private boolean settle(OutboxEvent event, IntSupplier markTransaction) {
    Integer updated =
        transactions.execute(
            status -> {
              int rows = markTransaction.getAsInt();
              repo.save(event);
              return rows;
            });
    return updated != null && updated > 0;
  }

  /**
   * A {@code TransactionCreated} event becomes a balance adjustment keyed by the transaction id, so
   * a replay of an applied adjustment is a no-op on account-service's side. The fraud rules screen
   * it first; their verdict is stored, so a redelivery gets the same one. Returns the transaction's
   * type.
   *
   * @throws SuspectedFraudException the fraud rules blocked it; nothing was sent
   */
  private TransactionType deliver(OutboxEvent event) {
    if (!TransactionCreatedEvent.EVENT_TYPE.equals(event.getEventType())) {
      throw new IllegalArgumentException("No delivery for event type " + event.getEventType());
    }
    TransactionCreatedEvent payload;
    try {
      payload = objectMapper.readValue(event.getPayload(), TransactionCreatedEvent.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unreadable payload: " + e.getOriginalMessage(), e);
    }
    FraudVerdict verdict =
        fraudRules.screen(
            new FraudContext(
                payload.transactionId(),
                payload.accountId(),
                payload.type(),
                payload.amount(),
                payload.createdAt(),
                () -> accountServiceClient.getAccount(payload.accountId())));
    if (verdict.blocked()) {
      throw new SuspectedFraudException(verdict.signals());
    }
    accountServiceClient.applyBalanceDelta(
        payload.accountId(), payload.balanceDelta(), payload.transactionId());
    return payload.type();
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

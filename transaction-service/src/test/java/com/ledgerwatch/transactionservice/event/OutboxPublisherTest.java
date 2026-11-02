package com.ledgerwatch.transactionservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.client.AccountServiceUnavailableException;
import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
  private static final OutboxPublisherProperties PROPERTIES =
      new OutboxPublisherProperties(
          true,
          Duration.ofSeconds(1),
          10,
          Duration.ofMinutes(2),
          Duration.ofSeconds(1),
          Duration.ofMinutes(5));

  @Mock OutboxEventRepository repo;
  @Mock AccountServiceClient accountServiceClient;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private CircuitBreakerRegistry circuitBreakerRegistry;
  private OutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    publisher =
        new OutboxPublisher(
            repo,
            accountServiceClient,
            circuitBreakerRegistry,
            TransactionOperations.withoutTransaction(),
            objectMapper,
            PROPERTIES,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private OutboxEvent event(UUID transactionId, UUID accountId, TransactionType type, String amt)
      throws Exception {
    var payload =
        new TransactionCreatedEvent(
            transactionId, accountId, type, new BigDecimal(amt), null, NOW.minusSeconds(5));
    OutboxEvent event =
        new OutboxEvent(
            TransactionCreatedEvent.AGGREGATE_TYPE,
            transactionId,
            TransactionCreatedEvent.EVENT_TYPE,
            objectMapper.writeValueAsString(payload));
    ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
    return event;
  }

  private OutboxEvent event() throws Exception {
    return event(UUID.randomUUID(), UUID.randomUUID(), TransactionType.CREDIT, "10.00");
  }

  private void due(OutboxEvent... events) {
    when(repo.lockDue(NOW, PROPERTIES.batchSize())).thenReturn(List.of(events));
  }

  @Test
  void delivers_transactionCreatedAsBalanceAdjustmentKeyedByTransactionId() throws Exception {
    UUID transactionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OutboxEvent event = event(transactionId, accountId, TransactionType.DEBIT, "12.34");
    due(event);

    publisher.publishPending();

    verify(accountServiceClient)
        .applyBalanceDelta(accountId, new BigDecimal("-12.34"), transactionId);
    assertThat(event.getPublishedAt()).isEqualTo(NOW);
    assertThat(event.getAttempts()).isZero();
    verify(repo).save(event);
  }

  @Test
  void claim_leasesEventsBeforeDeliveringThem() throws Exception {
    OutboxEvent event = event();
    due(event);
    Instant[] dueWhileDelivering = new Instant[1];
    doAnswer(
            inv -> {
              dueWhileDelivering[0] = event.getNextAttemptAt();
              return null;
            })
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(dueWhileDelivering[0]).isEqualTo(NOW.plus(PROPERTIES.lease()));
  }

  @Test
  void unavailable_backsOffAndKeepsEventPending() throws Exception {
    OutboxEvent event = event();
    due(event);
    doThrow(new AccountServiceUnavailableException("account-service returned 503"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getFailedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
    assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(event.getLastError()).contains("503");
    verify(repo).save(event);
  }

  @Test
  void unavailable_againDoublesTheWait() throws Exception {
    OutboxEvent event = event();
    event.scheduleRetry(NOW, "earlier failure");
    event.scheduleRetry(NOW, "earlier failure");
    due(event);
    doThrow(new AccountServiceUnavailableException("down"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getAttempts()).isEqualTo(3);
    assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(4));
  }

  @Test
  void backoff_doublesFromInitialAndCapsAtMax() {
    assertThat(publisher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(publisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(publisher.backoff(3)).isEqualTo(Duration.ofSeconds(4));
    assertThat(publisher.backoff(9)).isEqualTo(Duration.ofSeconds(256));
    assertThat(publisher.backoff(10)).isEqualTo(Duration.ofMinutes(5));
    assertThat(publisher.backoff(1_000)).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void rejection_parksEventWithoutRetry() throws Exception {
    OutboxEvent event = event();
    due(event);
    doThrow(new NoSuchElementException("Account not found"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getFailedAt()).isEqualTo(NOW);
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getLastError()).isEqualTo("NoSuchElementException: Account not found");
    verify(repo).save(event);
  }

  @Test
  void unknownEventType_isParkedWithoutCallingAccountService() {
    OutboxEvent event = new OutboxEvent("Transaction", UUID.randomUUID(), "SomethingElse", "{}");
    due(event);

    publisher.publishPending();

    verifyNoInteractions(accountServiceClient);
    assertThat(event.getFailedAt()).isEqualTo(NOW);
    assertThat(event.getLastError()).contains("SomethingElse");
  }

  @Test
  void longErrorMessage_isTruncated() throws Exception {
    OutboxEvent event = event();
    due(event);
    doThrow(new IllegalStateException("x".repeat(OutboxPublisher.MAX_ERROR_LENGTH)))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getLastError()).hasSize(OutboxPublisher.MAX_ERROR_LENGTH);
  }

  @Test
  void breakerTrippingMidBatch_releasesTheRestUntouched() throws Exception {
    OutboxEvent first = event();
    OutboxEvent second = event();
    OutboxEvent third = event();
    due(first, second, third);
    var breaker = circuitBreakerRegistry.circuitBreaker(AccountServiceClient.RESILIENCE_INSTANCE);
    doNothing()
        .doThrow(CallNotPermittedException.createCallNotPermittedException(breaker))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    verify(accountServiceClient, times(2)).applyBalanceDelta(any(), any(), any());
    assertThat(first.getPublishedAt()).isEqualTo(NOW);
    for (OutboxEvent released : List.of(second, third)) {
      assertThat(released.getPublishedAt()).isNull();
      assertThat(released.getAttempts()).isZero();
      assertThat(released.getNextAttemptAt()).isEqualTo(NOW);
    }
    verify(repo).saveAll(List.of(second, third));
  }

  @Test
  void openBreaker_skipsThePollEntirely() {
    circuitBreakerRegistry
        .circuitBreaker(AccountServiceClient.RESILIENCE_INSTANCE)
        .transitionToOpenState();

    publisher.publishPending();

    verify(repo, never()).lockDue(any(), anyInt());
    verifyNoInteractions(accountServiceClient);
  }
}

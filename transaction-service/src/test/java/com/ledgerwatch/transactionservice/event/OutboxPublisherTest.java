package com.ledgerwatch.transactionservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.common.error.AccountNotActiveException;
import com.ledgerwatch.common.error.InsufficientFundsException;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.client.AccountServiceConflictException;
import com.ledgerwatch.transactionservice.client.AccountServiceException;
import com.ledgerwatch.transactionservice.client.AccountServiceUnavailableException;
import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import com.ledgerwatch.transactionservice.domain.TransactionRejectionReason;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.fraud.FraudContext;
import com.ledgerwatch.transactionservice.fraud.FraudRuleEngine;
import com.ledgerwatch.transactionservice.fraud.FraudSignal;
import com.ledgerwatch.transactionservice.fraud.FraudVerdict;
import com.ledgerwatch.transactionservice.metrics.TransactionMetrics;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.mockito.ArgumentCaptor;
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
  @Mock TransactionRepository transactionRepo;
  @Mock AccountServiceClient accountServiceClient;
  @Mock FraudRuleEngine fraudRules;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private CircuitBreakerRegistry circuitBreakerRegistry;
  private SimpleMeterRegistry meterRegistry;
  private OutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    meterRegistry = new SimpleMeterRegistry();
    lenient().when(fraudRules.screen(any())).thenReturn(FraudVerdict.CLEAR);
    publisher =
        new OutboxPublisher(
            repo,
            transactionRepo,
            accountServiceClient,
            circuitBreakerRegistry,
            TransactionOperations.withoutTransaction(),
            objectMapper,
            PROPERTIES,
            new TransactionMetrics(meterRegistry),
            fraudRules,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private double posted(TransactionType type) {
    return meterRegistry.get(TransactionMetrics.POSTED).tag("type", type.name()).counter().count();
  }

  private double rejected(TransactionRejectionReason reason) {
    return meterRegistry
        .get(TransactionMetrics.REJECTED)
        .tag("reason", reason.name())
        .counter()
        .count();
  }

  private double settledTotal() {
    return meterRegistry.get(TransactionMetrics.POSTED).counters().stream()
            .mapToDouble(c -> c.count())
            .sum()
        + meterRegistry.get(TransactionMetrics.REJECTED).counters().stream()
            .mapToDouble(c -> c.count())
            .sum();
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
    verify(transactionRepo).markPosted(transactionId, NOW);
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
  void screensTheTransactionAsCreated_beforeDelivering() throws Exception {
    UUID transactionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OutboxEvent event = event(transactionId, accountId, TransactionType.DEBIT, "12.34");
    due(event);

    publisher.publishPending();

    ArgumentCaptor<FraudContext> context = ArgumentCaptor.forClass(FraudContext.class);
    var order = inOrder(fraudRules, accountServiceClient);
    order.verify(fraudRules).screen(context.capture());
    order.verify(accountServiceClient).applyBalanceDelta(any(), any(), any());
    assertThat(context.getValue().transactionId()).isEqualTo(transactionId);
    assertThat(context.getValue().accountId()).isEqualTo(accountId);
    assertThat(context.getValue().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(context.getValue().amount()).isEqualByComparingTo("12.34");
    assertThat(context.getValue().createdAt()).isEqualTo(NOW.minusSeconds(5));
    // The account is only fetched if a rule asks for it.
    verify(accountServiceClient, never()).getAccount(any());
  }

  @Test
  void blockedByFraudRules_rejectsWithoutTouchingTheBalance() throws Exception {
    UUID transactionId = UUID.randomUUID();
    OutboxEvent event = event(transactionId, UUID.randomUUID(), TransactionType.DEBIT, "5000");
    due(event);
    when(fraudRules.screen(any()))
        .thenReturn(
            new FraudVerdict(List.of(new FraudSignal("VELOCITY", "11 transactions")), true));
    when(transactionRepo.markRejected(
            transactionId, TransactionRejectionReason.SUSPECTED_FRAUD, NOW))
        .thenReturn(1);

    publisher.publishPending();

    verify(accountServiceClient, never()).applyBalanceDelta(any(), any(), any());
    assertThat(event.getFailedAt()).isEqualTo(NOW);
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getLastError()).contains("VELOCITY").contains("11 transactions");
    assertThat(rejected(TransactionRejectionReason.SUSPECTED_FRAUD)).isEqualTo(1);
  }

  @Test
  void flaggedButNotBlocked_isDelivered() throws Exception {
    UUID transactionId = UUID.randomUUID();
    OutboxEvent event = event(transactionId, UUID.randomUUID(), TransactionType.DEBIT, "5000");
    due(event);
    when(fraudRules.screen(any()))
        .thenReturn(
            new FraudVerdict(List.of(new FraudSignal("AMOUNT_SPIKE", "monitor only")), false));

    publisher.publishPending();

    verify(accountServiceClient).applyBalanceDelta(any(), any(), any());
    verify(transactionRepo).markPosted(transactionId, NOW);
  }

  @Test
  void accountLookupUnavailableDuringScreening_backsOff() throws Exception {
    OutboxEvent event = event();
    due(event);
    when(fraudRules.screen(any()))
        .thenThrow(new AccountServiceUnavailableException("account-service returned 503"));

    publisher.publishPending();

    assertThat(event.getFailedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
    verify(accountServiceClient, never()).applyBalanceDelta(any(), any(), any());
    verifyNoInteractions(transactionRepo);
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
    verifyNoInteractions(transactionRepo);
  }

  @Test
  void conflict_backsOffLikeAnOutageAndDoesNotRejectTheTransaction() throws Exception {
    OutboxEvent event = event();
    due(event);
    doThrow(new AccountServiceConflictException("account-service returned 409: modified"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getFailedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
    assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    verifyNoInteractions(transactionRepo);
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
  void accountNotFound_parksEventAndRejectsTransaction() throws Exception {
    UUID transactionId = UUID.randomUUID();
    OutboxEvent event = event(transactionId, UUID.randomUUID(), TransactionType.DEBIT, "10.00");
    due(event);
    doThrow(new NoSuchElementException("Account not found"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getFailedAt()).isEqualTo(NOW);
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getLastError()).isEqualTo("NoSuchElementException: Account not found");
    verify(transactionRepo)
        .markRejected(transactionId, TransactionRejectionReason.ACCOUNT_NOT_FOUND, NOW);
    verify(repo).save(event);
  }

  @Test
  void accountNotActive_rejectsTransaction() throws Exception {
    UUID transactionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OutboxEvent event = event(transactionId, accountId, TransactionType.DEBIT, "10.00");
    due(event);
    doThrow(new AccountNotActiveException(accountId, "FROZEN"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getFailedAt()).isEqualTo(NOW);
    verify(transactionRepo)
        .markRejected(transactionId, TransactionRejectionReason.ACCOUNT_NOT_ACTIVE, NOW);
  }

  @Test
  void insufficientFunds_rejectsTransaction() throws Exception {
    UUID transactionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OutboxEvent event = event(transactionId, accountId, TransactionType.DEBIT, "10.00");
    due(event);
    doThrow(new InsufficientFundsException(accountId, new BigDecimal("10.00")))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getFailedAt()).isEqualTo(NOW);
    verify(transactionRepo)
        .markRejected(transactionId, TransactionRejectionReason.INSUFFICIENT_FUNDS, NOW);
  }

  @Test
  void authFailure_parksEventButLeavesTransactionPending() throws Exception {
    OutboxEvent event = event();
    due(event);
    doThrow(new AccountServiceException("account-service returned 403"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(event.getFailedAt()).isEqualTo(NOW);
    verify(repo).save(event);
    verifyNoInteractions(transactionRepo);
  }

  @Test
  void unknownEventType_isParkedWithoutCallingAccountService() {
    OutboxEvent event = new OutboxEvent("Transaction", UUID.randomUUID(), "SomethingElse", "{}");
    due(event);

    publisher.publishPending();

    verifyNoInteractions(accountServiceClient, transactionRepo);
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

  @Test
  void posted_isCountedByType() throws Exception {
    OutboxEvent debit = event(UUID.randomUUID(), UUID.randomUUID(), TransactionType.DEBIT, "1.00");
    OutboxEvent credit =
        event(UUID.randomUUID(), UUID.randomUUID(), TransactionType.CREDIT, "1.00");
    due(debit, credit);
    when(transactionRepo.markPosted(any(), any())).thenReturn(1);

    publisher.publishPending();

    assertThat(posted(TransactionType.DEBIT)).isEqualTo(1);
    assertThat(posted(TransactionType.CREDIT)).isEqualTo(1);
    assertThat(rejected(TransactionRejectionReason.INSUFFICIENT_FUNDS)).isZero();
  }

  @Test
  void redeliveryOfAnAlreadySettledTransaction_isNotCountedAgain() throws Exception {
    due(event());
    when(transactionRepo.markPosted(any(), any())).thenReturn(0);

    publisher.publishPending();

    assertThat(settledTotal()).isZero();
  }

  @Test
  void rejected_isCountedByReason() throws Exception {
    due(event());
    doThrow(new InsufficientFundsException(UUID.randomUUID(), new BigDecimal("10.00")))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());
    when(transactionRepo.markRejected(any(), any(), any())).thenReturn(1);

    publisher.publishPending();

    assertThat(rejected(TransactionRejectionReason.INSUFFICIENT_FUNDS)).isEqualTo(1);
    assertThat(rejected(TransactionRejectionReason.ACCOUNT_NOT_FOUND)).isZero();
    assertThat(posted(TransactionType.CREDIT)).isZero();
  }

  @Test
  void settlementThatFailsToCommit_isNotCounted() throws Exception {
    due(event());
    when(transactionRepo.markPosted(any(), any())).thenReturn(1);
    when(repo.save(any())).thenThrow(new IllegalStateException("connection lost"));

    assertThatThrownBy(publisher::publishPending).isInstanceOf(IllegalStateException.class);

    assertThat(settledTotal()).isZero();
  }

  @Test
  void transientFailureAndParkedEvents_areNeitherPostedNorRejected() throws Exception {
    OutboxEvent unavailable = event();
    OutboxEvent forbidden = event();
    due(unavailable, forbidden);
    doThrow(new AccountServiceUnavailableException("down"))
        .doThrow(new AccountServiceException("account-service returned 403"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());

    publisher.publishPending();

    assertThat(settledTotal()).isZero();
  }
}

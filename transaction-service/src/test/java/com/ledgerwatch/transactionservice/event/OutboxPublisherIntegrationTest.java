package com.ledgerwatch.transactionservice.event;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.ledgerwatch.common.logging.CorrelationId;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionRejectionReason;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs {@link OutboxPublisher} against real Postgres (claim query, row locks, persisted delivery
 * state) and a WireMock stand-in for account-service.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class OutboxPublisherIntegrationTest {

  private static final int MAX_ATTEMPTS = 3;

  @RegisterExtension
  static WireMockExtension accountService =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("account-service.base-url", accountService::baseUrl);
    String retry = "resilience4j.retry.instances.accountService.";
    registry.add(retry + "max-attempts", () -> MAX_ATTEMPTS);
    registry.add(retry + "wait-duration", () -> "10ms");
  }

  @Autowired OutboxPublisher publisher;
  @Autowired OutboxEventRepository outboxEventRepository;
  @Autowired TransactionRepository transactionRepository;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionTemplate transactionTemplate;
  @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

  @BeforeEach
  void resetBreaker() {
    circuitBreakerRegistry.circuitBreaker(AccountServiceClient.RESILIENCE_INSTANCE).reset();
  }

  @AfterEach
  void cleanUp() {
    outboxEventRepository.deleteAll();
  }

  private record Pending(UUID eventId, UUID transactionId, UUID accountId) {}

  private Pending pendingEvent(TransactionType type, String amount) throws Exception {
    UUID transactionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    var payload =
        new TransactionCreatedEvent(
            transactionId, accountId, type, new BigDecimal(amount), "Coffee", Instant.now());
    OutboxEvent saved =
        outboxEventRepository.save(
            new OutboxEvent(
                TransactionCreatedEvent.AGGREGATE_TYPE,
                transactionId,
                TransactionCreatedEvent.EVENT_TYPE,
                objectMapper.writeValueAsString(payload)));
    return new Pending(saved.getId(), transactionId, accountId);
  }

  private static String adjustmentPath(UUID accountId) {
    return "/internal/accounts/" + accountId + "/balance-adjustments";
  }

  private static void stubAdjustment(UUID accountId, int status) {
    accountService.stubFor(
        post(urlEqualTo(adjustmentPath(accountId)))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));
  }

  private OutboxEvent reload(Pending pending) {
    return outboxEventRepository.findById(pending.eventId()).orElseThrow();
  }

  @Test
  void pendingEvent_isDeliveredAsBalanceAdjustment_andMarkedPublished() throws Exception {
    Pending pending = pendingEvent(TransactionType.DEBIT, "12.34");
    stubAdjustment(pending.accountId(), 200);

    publisher.publishPending();

    accountService.verify(
        1,
        postRequestedFor(urlEqualTo(adjustmentPath(pending.accountId())))
            .withRequestBody(
                equalToJson(
                    "{\"adjustmentId\": \"" + pending.transactionId() + "\", \"delta\": -12.34}")));
    OutboxEvent event = reload(pending);
    assertThat(event.getPublishedAt()).isNotNull();
    assertThat(event.getAttempts()).isZero();

    publisher.publishPending();

    accountService.verify(1, anyRequestedFor(anyUrl()));
  }

  @Test
  void eventWithoutACorrelationId_isStillDeliveredWithOne() throws Exception {
    // Rows written before V6 have no correlation id; delivery mints one rather than sending none.
    Pending pending = pendingEvent(TransactionType.CREDIT, "1.00");
    stubAdjustment(pending.accountId(), 200);

    publisher.publishPending();

    String sent =
        accountService.getAllServeEvents().get(0).getRequest().getHeader(CorrelationId.HEADER);
    assertThat(CorrelationId.isValid(sent)).isTrue();
    assertThat(CorrelationId.current()).as("MDC is cleared after the poll").isNull();
  }

  @Test
  void unavailableAccountService_backsOff_andTheEventIsNotDueAgainYet() throws Exception {
    Pending pending = pendingEvent(TransactionType.CREDIT, "50.00");
    stubAdjustment(pending.accountId(), 503);
    Instant before = Instant.now();

    publisher.publishPending();

    accountService.verify(MAX_ATTEMPTS, anyRequestedFor(anyUrl()));
    OutboxEvent event = reload(pending);
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getFailedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
    assertThat(event.getNextAttemptAt()).isAfter(before);
    assertThat(event.getLastError()).contains("503");

    publisher.publishPending();

    accountService.verify(MAX_ATTEMPTS, anyRequestedFor(anyUrl()));
  }

  @Test
  void rejectedEvent_isParked_andNeverRetried() throws Exception {
    Pending pending = pendingEvent(TransactionType.CREDIT, "50.00");
    stubAdjustment(pending.accountId(), 404);

    publisher.publishPending();
    publisher.publishPending();

    accountService.verify(1, anyRequestedFor(anyUrl()));
    OutboxEvent event = reload(pending);
    assertThat(event.getFailedAt()).isNotNull();
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getLastError()).contains("Account not found");
  }

  @Test
  void redelivery_doesNotUndoALaterStatusChange() throws Exception {
    Transaction tx = new Transaction();
    tx.setAccountId(UUID.randomUUID());
    tx.setType(TransactionType.CREDIT);
    tx.setAmount(new BigDecimal("50.00"));
    tx.setStatus(TransactionStatus.VOIDED);
    UUID id = transactionRepository.save(tx).getId();

    transactionTemplate.executeWithoutResult(
        status -> {
          assertThat(transactionRepository.markPosted(id, Instant.now())).isZero();
          assertThat(
                  transactionRepository.markRejected(
                      id, TransactionRejectionReason.ACCOUNT_NOT_FOUND, Instant.now()))
              .isZero();
        });

    Transaction reloaded = transactionRepository.findById(id).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.VOIDED);
    assertThat(reloaded.getRejectionReason()).isNull();
    transactionRepository.deleteAll();
  }

  @Test
  void eventLockedByAnotherPublisher_isSkipped() throws Exception {
    Pending pending = pendingEvent(TransactionType.CREDIT, "50.00");
    stubAdjustment(pending.accountId(), 200);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);

    // Another publisher's claim transaction, holding the row lock until we let it go.
    CompletableFuture<Void> other =
        CompletableFuture.runAsync(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      assertThat(outboxEventRepository.lockDue(Instant.now(), 10)).hasSize(1);
                      locked.countDown();
                      try {
                        done.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    publisher.publishPending();

    done.countDown();
    other.get(10, TimeUnit.SECONDS);
    accountService.verify(0, anyRequestedFor(anyUrl()));
    assertThat(reload(pending).getPublishedAt()).isNull();
  }
}

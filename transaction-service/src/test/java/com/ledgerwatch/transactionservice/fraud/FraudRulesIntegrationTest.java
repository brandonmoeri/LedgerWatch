package com.ledgerwatch.transactionservice.fraud;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.domain.FraudScreening;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionRejectionReason;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.event.OutboxPublisher;
import com.ledgerwatch.transactionservice.repository.FraudScreeningRepository;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import com.ledgerwatch.transactionservice.service.TransactionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The fraud rules end to end: real queries against Postgres, the real {@code OutboxPublisher}, and
 * a WireMock stand-in for account-service. Thresholds are lowered so a handful of transactions is
 * enough to trip each rule.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class FraudRulesIntegrationTest {

  private static final int MAX_VELOCITY = 3;

  @RegisterExtension
  static WireMockExtension accountService =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("account-service.base-url", accountService::baseUrl);
    registry.add("resilience4j.retry.instances.accountService.wait-duration", () -> "10ms");
    registry.add("fraud.velocity.max-transactions", () -> MAX_VELOCITY);
    registry.add("fraud.amount-spike.min-history", () -> 2);
  }

  @Autowired TransactionService transactionService;
  @Autowired TransactionRepository transactionRepository;
  @Autowired OutboxEventRepository outboxEventRepository;
  @Autowired FraudScreeningRepository screeningRepository;
  @Autowired OutboxPublisher publisher;
  @Autowired ObjectMapper objectMapper;

  @AfterEach
  void cleanUp() {
    outboxEventRepository.deleteAll();
    screeningRepository.deleteAll();
    transactionRepository.deleteAll();
    accountService.resetAll();
  }

  private static UUID accountAcceptingAdjustments() {
    UUID accountId = UUID.randomUUID();
    accountService.stubFor(
        post(urlEqualTo("/internal/accounts/" + accountId + "/balance-adjustments"))
            .willReturn(okJson("{}")));
    return accountId;
  }

  private static void stubAccountCreated(UUID accountId, Instant createdAt) {
    accountService.stubFor(
        get(urlEqualTo("/internal/accounts/" + accountId))
            .willReturn(
                okJson(
                    """
                    {"id": "%s", "ownerName": "Test", "status": "ACTIVE", "balance": 100000,
                     "createdAt": "%s", "updatedAt": "%s"}
                    """
                        .formatted(accountId, createdAt, createdAt))));
  }

  /** Creates a transaction and runs the publisher, so it is settled when this returns. */
  private Transaction createAndSettle(UUID accountId, TransactionType type, String amount) {
    Transaction tx =
        transactionService.createTransaction(
            new CreateTransactionRequest(accountId, type, new BigDecimal(amount), null));
    publisher.publishPending();
    return transactionRepository.findById(tx.getId()).orElseThrow();
  }

  private void assertBlockedBy(Transaction tx, String rule) throws Exception {
    assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(tx.getRejectionReason()).isEqualTo(TransactionRejectionReason.SUSPECTED_FRAUD);
    FraudScreening screening = screeningRepository.findById(tx.getId()).orElseThrow();
    assertThat(screening.isBlocked()).isTrue();
    JsonNode signals = objectMapper.readTree(screening.getSignals());
    assertThat(signals).extracting(s -> s.get("rule").asText()).containsExactly(rule);
  }

  private static int adjustmentsSent(UUID accountId) {
    return accountService
        .findAll(
            postRequestedFor(
                urlEqualTo("/internal/accounts/" + accountId + "/balance-adjustments")))
        .size();
  }

  @Test
  void velocity_blocksTheTransactionOverTheLimit() throws Exception {
    UUID accountId = accountAcceptingAdjustments();
    for (int i = 0; i < MAX_VELOCITY; i++) {
      assertThat(createAndSettle(accountId, TransactionType.CREDIT, "10.00").getStatus())
          .isEqualTo(TransactionStatus.POSTED);
    }

    Transaction over = createAndSettle(accountId, TransactionType.CREDIT, "10.00");

    assertBlockedBy(over, VelocityRule.NAME);
    assertThat(adjustmentsSent(accountId)).isEqualTo(MAX_VELOCITY);
  }

  @Test
  void amountSpike_blocksMoreThanThreeTimesTheAverageOfTheSameType() throws Exception {
    UUID accountId = accountAcceptingAdjustments();
    createAndSettle(accountId, TransactionType.DEBIT, "10.00");
    createAndSettle(accountId, TransactionType.DEBIT, "20.00"); // DEBIT average 15

    Transaction spike = createAndSettle(accountId, TransactionType.DEBIT, "45.01");

    assertBlockedBy(spike, AmountSpikeRule.NAME);
    assertThat(adjustmentsSent(accountId)).isEqualTo(2);
  }

  @Test
  void amountSpike_atThreeTimesTheAverage_posts() {
    UUID accountId = accountAcceptingAdjustments();
    createAndSettle(accountId, TransactionType.DEBIT, "10.00");
    createAndSettle(accountId, TransactionType.DEBIT, "20.00");

    assertThat(createAndSettle(accountId, TransactionType.DEBIT, "45.00").getStatus())
        .isEqualTo(TransactionStatus.POSTED);
  }

  @Test
  void largeDebitOnNewAccount_isBlocked() throws Exception {
    UUID accountId = accountAcceptingAdjustments();
    stubAccountCreated(accountId, Instant.now().minus(Duration.ofHours(2)));

    Transaction debit = createAndSettle(accountId, TransactionType.DEBIT, "1500.00");

    assertBlockedBy(debit, NewAccountLargeDebitRule.NAME);
    assertThat(adjustmentsSent(accountId)).isZero();
  }

  @Test
  void largeDebitOnEstablishedAccount_posts() {
    UUID accountId = accountAcceptingAdjustments();
    stubAccountCreated(accountId, Instant.now().minus(Duration.ofDays(2)));

    Transaction debit = createAndSettle(accountId, TransactionType.DEBIT, "1500.00");

    assertThat(debit.getStatus()).isEqualTo(TransactionStatus.POSTED);
    FraudScreening screening = screeningRepository.findById(debit.getId()).orElseThrow();
    assertThat(screening.isBlocked()).isFalse();
    assertThat(screening.getSignals()).isEqualTo("[]");
  }

  @Test
  void smallDebit_doesNotLookUpTheAccount() {
    UUID accountId = accountAcceptingAdjustments();

    createAndSettle(accountId, TransactionType.DEBIT, "999.99");

    accountService.verify(0, getRequestedFor(urlEqualTo("/internal/accounts/" + accountId)));
  }

  @Test
  void accountLookupFailing_keepsTheTransactionPendingForRetry() {
    UUID accountId = accountAcceptingAdjustments();
    accountService.stubFor(
        get(urlEqualTo("/internal/accounts/" + accountId)).willReturn(aResponse().withStatus(503)));

    Transaction debit = createAndSettle(accountId, TransactionType.DEBIT, "1500.00");

    assertThat(debit.getStatus()).isEqualTo(TransactionStatus.PENDING);
    assertThat(screeningRepository.findById(debit.getId())).isEmpty();
    assertThat(adjustmentsSent(accountId)).isZero();
  }
}

package com.ledgerwatch.transactionservice.fraud;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.event.OutboxPublisher;
import com.ledgerwatch.transactionservice.repository.FraudScreeningRepository;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import com.ledgerwatch.transactionservice.service.TransactionService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Fraud flags end to end: each rule firing on a real posting attempt, then read back through {@code
 * GET /accounts/{id}/flags}. Thresholds are lowered as in {@link FraudRulesIntegrationTest}.
 * MONITOR mode, which needs its own context, is in {@link
 * AccountFraudFlagsMonitorModeIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class AccountFraudFlagsIntegrationTest {

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

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionService transactionService;
  @Autowired TransactionRepository transactionRepository;
  @Autowired OutboxEventRepository outboxEventRepository;
  @Autowired FraudScreeningRepository screeningRepository;
  @Autowired OutboxPublisher publisher;

  @AfterEach
  void cleanUp() {
    outboxEventRepository.deleteAll();
    screeningRepository.deleteAll(); // cascades to fraud_flag
    transactionRepository.deleteAll();
    accountService.resetAll();
  }

  static UUID accountAcceptingAdjustments(WireMockExtension accountService) {
    UUID accountId = UUID.randomUUID();
    accountService.stubFor(
        post(urlEqualTo("/internal/accounts/" + accountId + "/balance-adjustments"))
            .willReturn(okJson("{}")));
    return accountId;
  }

  static void stubAccountAged(WireMockExtension accountService, UUID accountId, Duration age) {
    Instant createdAt = Instant.now().minus(age);
    accountService.stubFor(
        WireMock.get(urlEqualTo("/internal/accounts/" + accountId))
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

  static MockHttpServletRequestBuilder flagsRequest(UUID accountId) {
    return get("/accounts/{id}/flags", accountId)
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ANALYST")));
  }

  private JsonNode flags(UUID accountId) throws Exception {
    return page(flagsRequest(accountId)).get("content");
  }

  private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  static void assertSingleFlag(JsonNode flags, Transaction tx, String rule, boolean blocked) {
    assertThat(flags).hasSize(1);
    JsonNode flag = flags.get(0);
    assertThat(flag.get("rule").asText()).isEqualTo(rule);
    assertThat(flag.get("transactionId").asText()).isEqualTo(tx.getId().toString());
    assertThat(flag.get("accountId").asText()).isEqualTo(tx.getAccountId().toString());
    assertThat(flag.get("blocked").asBoolean()).isEqualTo(blocked);
    assertThat(flag.get("detail").asText()).isNotBlank();
    assertThat(flag.get("id").asText()).isNotBlank();
    assertThat(flag.get("flaggedAt").asText()).isNotBlank();
  }

  @Test
  void velocity_isFlaggedOnTheTransactionOverTheLimit() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    for (int i = 0; i < MAX_VELOCITY; i++) {
      createAndSettle(accountId, TransactionType.CREDIT, "10.00");
    }

    Transaction over = createAndSettle(accountId, TransactionType.CREDIT, "10.00");

    assertThat(over.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    JsonNode flags = flags(accountId);
    assertSingleFlag(flags, over, VelocityRule.NAME, true);
    assertThat(flags.get(0).get("detail").asText())
        .isEqualTo("4 transactions in PT10M (max " + MAX_VELOCITY + ")");
  }

  @Test
  void amountSpike_isFlagged() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    createAndSettle(accountId, TransactionType.DEBIT, "10.00");
    createAndSettle(accountId, TransactionType.DEBIT, "20.00"); // DEBIT average 15

    Transaction spike = createAndSettle(accountId, TransactionType.DEBIT, "45.01");

    assertThat(spike.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertSingleFlag(flags(accountId), spike, AmountSpikeRule.NAME, true);
  }

  @Test
  void newAccountLargeDebit_isFlagged() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    stubAccountAged(accountService, accountId, Duration.ofHours(2));

    Transaction debit = createAndSettle(accountId, TransactionType.DEBIT, "1500.00");

    assertThat(debit.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertSingleFlag(flags(accountId), debit, NewAccountLargeDebitRule.NAME, true);
  }

  @Test
  void transactionsNoRuleFlags_leaveNoFlags() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    stubAccountAged(accountService, accountId, Duration.ofDays(2));

    createAndSettle(accountId, TransactionType.CREDIT, "10.00");
    createAndSettle(accountId, TransactionType.DEBIT, "1500.00"); // large, but not a new account

    assertThat(flags(accountId)).isEmpty();
  }

  @Test
  void oneTransactionTrippingTwoRules_hasAFlagForEach() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    stubAccountAged(accountService, accountId, Duration.ofHours(2));
    for (int i = 0; i < MAX_VELOCITY; i++) {
      createAndSettle(accountId, TransactionType.CREDIT, "10.00");
    }

    Transaction debit = createAndSettle(accountId, TransactionType.DEBIT, "1500.00");

    JsonNode flags = flags(accountId);
    assertThat(flags)
        .extracting(f -> f.get("rule").asText())
        .containsExactlyInAnyOrder(VelocityRule.NAME, NewAccountLargeDebitRule.NAME);
    assertThat(flags)
        .extracting(f -> f.get("transactionId").asText())
        .containsOnly(debit.getId().toString());
  }

  @Test
  void redelivery_doesNotDuplicateFlags() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    stubAccountAged(accountService, accountId, Duration.ofHours(2));
    Transaction debit = createAndSettle(accountId, TransactionType.DEBIT, "1500.00");

    // As if the event had been claimed again after a crash: the stored verdict is replayed.
    jdbc.update(
        "UPDATE outbox_event SET failed_at = NULL, next_attempt_at = now() WHERE aggregate_id = ?",
        debit.getId());
    publisher.publishPending();

    assertSingleFlag(flags(accountId), debit, NewAccountLargeDebitRule.NAME, true);
  }

  @Test
  void listsOnlyThisAccount_newestFirst_paged() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    UUID otherAccount = accountAcceptingAdjustments(accountService);
    stubAccountAged(accountService, accountId, Duration.ofHours(2));
    stubAccountAged(accountService, otherAccount, Duration.ofHours(2));
    Transaction first = createAndSettle(accountId, TransactionType.DEBIT, "1500.00");
    createAndSettle(otherAccount, TransactionType.DEBIT, "1500.00");
    Transaction second = createAndSettle(accountId, TransactionType.DEBIT, "2000.00");

    JsonNode all = flags(accountId);
    assertThat(all)
        .extracting(f -> f.get("transactionId").asText())
        .containsExactly(second.getId().toString(), first.getId().toString());

    JsonNode pageOne = page(flagsRequest(accountId).param("size", "1"));
    assertThat(pageOne.get("content")).hasSize(1);
    assertThat(pageOne.get("content").get(0).get("transactionId").asText())
        .isEqualTo(second.getId().toString());
    assertThat(pageOne.get("page").get("totalElements").asLong()).isEqualTo(2);
  }

  @Test
  void unknownAccount_isAnEmptyPage() throws Exception {
    JsonNode page = page(flagsRequest(UUID.randomUUID()));

    assertThat(page.get("content")).isEmpty();
    assertThat(page.get("page").get("totalElements").asLong()).isZero();
  }

  @Test
  void requiresAToken() throws Exception {
    mockMvc
        .perform(get("/accounts/{id}/flags", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void migrationBackfill_createsFlagsForScreeningsStoredBeforeTheTable() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    Transaction tx = createAndSettle(accountId, TransactionType.CREDIT, "10.00");
    // A screening as the previous release stored it: signals in JSON, no flag rows.
    jdbc.update(
        "UPDATE fraud_screening SET blocked = true, signals = CAST(? AS jsonb),"
            + " screened_at = '2026-11-01T12:00:00Z' WHERE transaction_id = ?",
        "[{\"rule\": \"VELOCITY\", \"detail\": \"old\"}, {\"rule\": \"AMOUNT_SPIKE\", \"detail\": \"older\"}]",
        tx.getId());

    jdbc.execute(backfillStatement());
    jdbc.execute(backfillStatement()); // re-running it adds nothing

    JsonNode flags = flags(accountId);
    assertThat(flags)
        .extracting(f -> f.get("rule").asText() + ":" + f.get("detail").asText())
        .containsExactlyInAnyOrder("VELOCITY:old", "AMOUNT_SPIKE:older");
    assertThat(flags).allSatisfy(f -> assertThat(f.get("blocked").asBoolean()).isTrue());
    assertThat(flags)
        .allSatisfy(f -> assertThat(f.get("flaggedAt").asText()).isEqualTo("2026-11-01T12:00:00Z"));
  }

  /** The INSERT at the end of V8, which back-fills flags from fraud_screening.signals. */
  private static String backfillStatement() throws Exception {
    String sql =
        new ClassPathResource("db/migration/V8__fraud_flag.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    return sql.substring(sql.lastIndexOf("INSERT INTO transactions.fraud_flag"));
  }
}

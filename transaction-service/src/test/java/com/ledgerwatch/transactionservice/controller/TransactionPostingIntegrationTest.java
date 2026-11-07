package com.ledgerwatch.transactionservice.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.event.OutboxPublisher;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Follows a transaction from {@code POST /transactions} to its final status: the real {@code
 * AccountServiceClient} (RestClient, timeouts, service JWT, retry and circuit breaker) and {@code
 * OutboxPublisher} against a WireMock stand-in for account-service's balance-adjustment endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class TransactionPostingIntegrationTest {

  private static final String READ_TIMEOUT_MS = "500";
  private static final int MAX_ATTEMPTS = 3;
  // Small enough that a test can trip the breaker, large enough that no single request does.
  private static final int BREAKER_WINDOW = MAX_ATTEMPTS + 1;

  @RegisterExtension
  static WireMockExtension accountService =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void accountServiceProperties(DynamicPropertyRegistry registry) {
    registry.add("account-service.base-url", accountService::baseUrl);
    registry.add("account-service.read-timeout", () -> READ_TIMEOUT_MS + "ms");
    String retry = "resilience4j.retry.instances.accountService.";
    registry.add(retry + "max-attempts", () -> MAX_ATTEMPTS);
    registry.add(retry + "wait-duration", () -> "10ms");
    String breaker = "resilience4j.circuitbreaker.instances.accountService.";
    registry.add(breaker + "sliding-window-size", () -> BREAKER_WINDOW);
    registry.add(breaker + "minimum-number-of-calls", () -> BREAKER_WINDOW);
    registry.add(breaker + "wait-duration-in-open-state", () -> "1h");
  }

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionRepository transactionRepository;
  @Autowired OutboxEventRepository outboxEventRepository;
  @Autowired OutboxPublisher publisher;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

  private CircuitBreaker breaker() {
    return circuitBreakerRegistry.circuitBreaker("accountService");
  }

  @BeforeEach
  void resetBreaker() {
    breaker().reset();
  }

  @AfterEach
  void cleanUp() {
    outboxEventRepository.deleteAll();
    transactionRepository.deleteAll();
  }

  private static String adjustmentPath(UUID accountId) {
    return "/internal/accounts/" + accountId + "/balance-adjustments";
  }

  private void stubAdjustment(UUID accountId, ResponseDefinitionBuilder response) {
    accountService.stubFor(post(urlEqualTo(adjustmentPath(accountId))).willReturn(response));
  }

  private void stubAdjustment(UUID accountId, int status) {
    stubAdjustment(
        accountId,
        aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody("{}"));
  }

  private void stubConflict(UUID accountId, String problemBody) {
    stubAdjustment(
        accountId,
        aResponse()
            .withStatus(409)
            .withHeader("Content-Type", "application/problem+json")
            .withBody(problemBody));
  }

  private void verifyAttempts(int count, UUID accountId) {
    accountService.verify(count, postRequestedFor(urlEqualTo(adjustmentPath(accountId))));
  }

  /** Creates a transaction and returns its id, without anything having been delivered yet. */
  private String create(UUID accountId, TransactionType type, String amount) throws Exception {
    var request = new CreateTransactionRequest(accountId, type, new BigDecimal(amount), null);
    var result =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/transactions")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.rejectionReason").isEmpty())
            .andExpect(jsonPath("$.accountId").value(accountId.toString()))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private ResultActions getTransaction(String id) throws Exception {
    return mockMvc
        .perform(MockMvcRequestBuilders.get("/transactions/{id}", id).with(jwt()))
        .andExpect(status().isOk());
  }

  private void assertStatus(String id, String status) throws Exception {
    getTransaction(id)
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.rejectionReason").isEmpty());
  }

  private void assertRejected(String id, String reason) throws Exception {
    getTransaction(id)
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value(reason));
  }

  private OutboxEvent eventFor(String id) {
    var events = outboxEventRepository.findByAggregateIdOrderByCreatedAt(UUID.fromString(id));
    assertThat(events).hasSize(1);
    return events.get(0);
  }

  @Test
  void create_doesNotCallAccountService_untilTheEventIsPublished() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 200);

    String id = create(accountId, TransactionType.CREDIT, "250.00");

    verifyAttempts(0, accountId);
    assertStatus(id, "PENDING");

    publisher.publishPending();

    verifyAttempts(1, accountId);
    assertStatus(id, "POSTED");
    assertThat(eventFor(id).getPublishedAt()).isNotNull();
  }

  @Test
  void credit_postsPositiveDeltaWithServiceToken() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 200);

    String id = create(accountId, TransactionType.CREDIT, "250.00");
    publisher.publishPending();

    accountService.verify(
        1,
        postRequestedFor(urlEqualTo(adjustmentPath(accountId)))
            .withHeader("Content-Type", equalTo("application/json"))
            .withHeader("Authorization", matching("Bearer .+"))
            .withRequestBody(equalToJson("{\"delta\": 250.0000}", false, true))
            .withRequestBody(matchingJsonPath("$.adjustmentId", equalTo(id))));

    // The token must pass the same HS256 validation account-service applies, with the SERVICE role.
    String authorization =
        accountService.getAllServeEvents().get(0).getRequest().getHeader("Authorization");
    Jwt token = jwtDecoder.decode(authorization.substring("Bearer ".length()));
    assertThat(token.getSubject()).isEqualTo("transaction-service");
    assertThat(token.getClaimAsStringList("roles")).containsExactly("SERVICE");
  }

  @Test
  void create_commitsTransactionCreatedOutboxEventWithTheRow() throws Exception {
    UUID accountId = UUID.randomUUID();

    String id = create(accountId, TransactionType.CREDIT, "250.00");

    var event = eventFor(id);
    assertThat(event.getAggregateType()).isEqualTo("Transaction");
    assertThat(event.getEventType()).isEqualTo("TransactionCreated");
    assertThat(event.getPublishedAt()).isNull();
    var payload = objectMapper.readTree(event.getPayload());
    assertThat(payload.get("transactionId").asText()).isEqualTo(id);
    assertThat(payload.get("accountId").asText()).isEqualTo(accountId.toString());
    assertThat(payload.get("type").asText()).isEqualTo("CREDIT");
    assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("250.00");
  }

  @Test
  void debit_postsNegativeDelta() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 200);

    create(accountId, TransactionType.DEBIT, "40.50");
    publisher.publishPending();

    accountService.verify(
        postRequestedFor(urlEqualTo(adjustmentPath(accountId)))
            .withRequestBody(equalToJson("{\"delta\": -40.50}", false, true)));
  }

  @Test
  void accountNotFound_rejectsTransaction() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 404);

    String id = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();
    publisher.publishPending();

    verifyAttempts(1, accountId);
    assertRejected(id, "ACCOUNT_NOT_FOUND");
    assertThat(eventFor(id).getFailedAt()).isNotNull();
  }

  @Test
  void insufficientFunds_rejectsTransaction() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 422);

    String id = create(accountId, TransactionType.DEBIT, "40.50");
    publisher.publishPending();

    verifyAttempts(1, accountId);
    assertRejected(id, "INSUFFICIENT_FUNDS");
  }

  @Test
  void frozenAccount_rejectsTransaction() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubConflict(
        accountId,
        """
        {"type": "urn:ledgerwatch:problem:account-not-active", "status": 409,
         "detail": "Account is FROZEN", "accountStatus": "FROZEN"}
        """);

    String id = create(accountId, TransactionType.DEBIT, "10.00");
    publisher.publishPending();

    verifyAttempts(1, accountId);
    assertRejected(id, "ACCOUNT_NOT_ACTIVE");
    assertThat(eventFor(id).getLastError()).contains("FROZEN");
  }

  @Test
  void concurrentModificationConflict_staysPending_andIsRetried() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubConflict(
        accountId,
        """
        {"type": "about:blank", "status": 409,
         "detail": "Account was modified concurrently; re-read and retry"}
        """);

    String id = create(accountId, TransactionType.DEBIT, "10.00");
    publisher.publishPending();

    verifyAttempts(1, accountId);
    assertStatus(id, "PENDING");
    var event = eventFor(id);
    assertThat(event.getFailedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
    assertThat(event.getLastError()).contains("modified concurrently");
    assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  void conflictWithoutABody_isRetriedRatherThanRejected() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, aResponse().withStatus(409));

    String id = create(accountId, TransactionType.DEBIT, "10.00");
    publisher.publishPending();

    assertStatus(id, "PENDING");
    assertThat(eventFor(id).getFailedAt()).isNull();
  }

  @Test
  void accountServiceError_isRetried_andStaysPending() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 500);

    String id = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();

    verifyAttempts(MAX_ATTEMPTS, accountId);
    assertStatus(id, "PENDING");
    assertThat(eventFor(id).getFailedAt()).isNull();
  }

  @Test
  void accountServiceRejectsServiceToken_parksEventWithoutRetry_andStaysPending() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 403);

    String id = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();

    // A broken credential says nothing about the transaction, so it must not be rejected.
    verifyAttempts(1, accountId);
    assertStatus(id, "PENDING");
    assertThat(eventFor(id).getFailedAt()).isNotNull();
  }

  @Test
  void accountServiceSlowerThanReadTimeout_isRetried_andStaysPending() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(
        accountId,
        aResponse().withStatus(200).withFixedDelay(Integer.parseInt(READ_TIMEOUT_MS) * 4));

    String id = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();

    verifyAttempts(MAX_ATTEMPTS, accountId);
    assertStatus(id, "PENDING");
  }

  @Test
  void transient503_isRetriedWithSameAdjustmentId_andPosts() throws Exception {
    UUID accountId = UUID.randomUUID();
    accountService.stubFor(
        post(urlEqualTo(adjustmentPath(accountId)))
            .inScenario("flaky")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("recovered"));
    accountService.stubFor(
        post(urlEqualTo(adjustmentPath(accountId)))
            .inScenario("flaky")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody("{}")));

    String id = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();

    assertStatus(id, "POSTED");
    accountService.verify(
        2,
        postRequestedFor(urlEqualTo(adjustmentPath(accountId)))
            .withRequestBody(matchingJsonPath("$.adjustmentId", equalTo(id))));
  }

  @Test
  void repeatedFailures_openBreaker_whichStopsDelivery_andShowsOpenOnActuator() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 500);

    // MAX_ATTEMPTS failures, then one more fills the window and trips the breaker mid-retry.
    String first = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();
    String second = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();
    assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    verifyAttempts(BREAKER_WINDOW, accountId);

    // While open, nothing reaches account-service, and creating transactions still works.
    String third = create(accountId, TransactionType.CREDIT, "10.00");
    publisher.publishPending();
    verifyAttempts(BREAKER_WINDOW, accountId);
    assertStatus(first, "PENDING");
    assertStatus(second, "PENDING");
    assertStatus(third, "PENDING");

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/actuator/circuitbreakers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.circuitBreakers.accountService.state").value("OPEN"));
  }

  @Test
  void businessRejections_doNotOpenBreaker() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 404);

    for (int i = 0; i < BREAKER_WINDOW * 2; i++) {
      create(accountId, TransactionType.CREDIT, "10.00");
    }
    publisher.publishPending();

    assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    verifyAttempts(BREAKER_WINDOW * 2, accountId);
  }

  @Test
  void circuitBreakerActuator_requiresAdmin() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/actuator/circuitbreakers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(MockMvcRequestBuilders.get("/actuator/circuitbreakers"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void overPreciseAmount_returns400_withoutCreatingAnything() throws Exception {
    var request =
        new CreateTransactionRequest(
            UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("1.00001"), null);

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/transactions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.amount").isNotEmpty());

    assertThat(transactionRepository.count()).isZero();
    assertThat(outboxEventRepository.count()).isZero();
  }
}

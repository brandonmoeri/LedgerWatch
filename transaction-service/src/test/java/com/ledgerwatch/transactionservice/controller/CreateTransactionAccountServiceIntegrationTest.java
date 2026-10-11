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
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
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
 * Exercises the real {@code AccountServiceClient} (RestClient, timeouts, service JWT, retry and
 * circuit breaker) against a WireMock stand-in for account-service's balance-adjustment endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class CreateTransactionAccountServiceIntegrationTest {

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
    transactionRepository.deleteAll();
  }

  private static String adjustmentPath(UUID accountId) {
    return "/internal/accounts/" + accountId + "/balance-adjustments";
  }

  private void stubAdjustment(UUID accountId, int status) {
    accountService.stubFor(
        post(urlEqualTo(adjustmentPath(accountId)))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));
  }

  private void verifyAttempts(int count, UUID accountId) {
    accountService.verify(count, postRequestedFor(urlEqualTo(adjustmentPath(accountId))));
  }

  private ResultActions createTransaction(UUID accountId, TransactionType type, String amount)
      throws Exception {
    var request = new CreateTransactionRequest(accountId, type, new BigDecimal(amount), null);
    return mockMvc.perform(
        MockMvcRequestBuilders.post("/transactions")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  @Test
  void credit_postsPositiveDeltaWithServiceToken_andPersists() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 200);

    createTransaction(accountId, TransactionType.CREDIT, "250.00")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accountId").value(accountId.toString()));

    accountService.verify(
        1,
        postRequestedFor(urlEqualTo(adjustmentPath(accountId)))
            .withHeader("Content-Type", equalTo("application/json"))
            .withHeader("Authorization", matching("Bearer .+"))
            .withRequestBody(equalToJson("{\"delta\": 250.00}", false, true))
            .withRequestBody(
                matchingJsonPath(
                    "$.adjustmentId",
                    equalTo(transactionRepository.findAll().get(0).getId().toString()))));
    assertThat(transactionRepository.count()).isEqualTo(1);

    // The token must pass the same HS256 validation account-service applies, with the SERVICE role.
    String authorization =
        accountService.getAllServeEvents().get(0).getRequest().getHeader("Authorization");
    Jwt token = jwtDecoder.decode(authorization.substring("Bearer ".length()));
    assertThat(token.getSubject()).isEqualTo("transaction-service");
    assertThat(token.getClaimAsStringList("roles")).containsExactly("SERVICE");
  }

  @Test
  void debit_postsNegativeDelta() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 200);

    createTransaction(accountId, TransactionType.DEBIT, "40.50").andExpect(status().isCreated());

    accountService.verify(
        postRequestedFor(urlEqualTo(adjustmentPath(accountId)))
            .withRequestBody(equalToJson("{\"delta\": -40.50}", false, true)));
  }

  @Test
  void accountNotFound_returns404_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 404);

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Account not found: " + accountId));

    verifyAttempts(1, accountId);
    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountRejectsChange_returns409_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 409);

    createTransaction(accountId, TransactionType.DEBIT, "10.00").andExpect(status().isConflict());

    verifyAttempts(1, accountId);
    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void insufficientFunds_returns422Problem_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 422);

    createTransaction(accountId, TransactionType.DEBIT, "40.50")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:ledgerwatch:problem:insufficient-funds"))
        .andExpect(jsonPath("$.title").value("Insufficient funds"))
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.amount").value(40.50));

    verifyAttempts(1, accountId);
    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountServiceError_isRetriedThenReturns502_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 500);

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isBadGateway());

    verifyAttempts(MAX_ATTEMPTS, accountId);
    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountServiceRejectsServiceToken_returns502_withoutRetry() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 403);

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isBadGateway());

    verifyAttempts(1, accountId);
    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountServiceSlowerThanReadTimeout_isRetriedThenReturns502_andRollsBackInsert()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    accountService.stubFor(
        post(urlEqualTo(adjustmentPath(accountId)))
            .willReturn(
                aResponse().withStatus(200).withFixedDelay(Integer.parseInt(READ_TIMEOUT_MS) * 4)));

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isBadGateway());

    verifyAttempts(MAX_ATTEMPTS, accountId);
    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void transient503_isRetriedWithSameAdjustmentId_andSucceeds() throws Exception {
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

    createTransaction(accountId, TransactionType.CREDIT, "10.00").andExpect(status().isCreated());

    String transactionId = transactionRepository.findAll().get(0).getId().toString();
    accountService.verify(
        2,
        postRequestedFor(urlEqualTo(adjustmentPath(accountId)))
            .withRequestBody(matchingJsonPath("$.adjustmentId", equalTo(transactionId))));
  }

  @Test
  void repeatedFailures_openBreaker_whichFailsFastWith503_andShowsOpenOnActuator()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 500);

    // MAX_ATTEMPTS failures, then one more fills the window and trips the breaker mid-retry.
    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isBadGateway());
    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isServiceUnavailable());
    assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    verifyAttempts(BREAKER_WINDOW, accountId);

    // While open, nothing reaches account-service.
    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("Account service is unavailable; try again later"));
    verifyAttempts(BREAKER_WINDOW, accountId);
    assertThat(transactionRepository.count()).isZero();

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
      createTransaction(accountId, TransactionType.CREDIT, "10.00")
          .andExpect(status().isNotFound());
    }

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
  void overPreciseAmount_returns400_withoutCallingAccountService() throws Exception {
    createTransaction(UUID.randomUUID(), TransactionType.CREDIT, "1.00001")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.amount").isNotEmpty());

    assertThat(accountService.getAllServeEvents()).isEmpty();
  }
}

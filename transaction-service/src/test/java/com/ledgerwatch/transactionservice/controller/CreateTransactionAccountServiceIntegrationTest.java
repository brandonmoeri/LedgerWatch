package com.ledgerwatch.transactionservice.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * Exercises the real {@code AccountServiceClient} (RestClient, timeouts, service JWT) against a
 * WireMock stand-in for account-service's balance-adjustment endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class CreateTransactionAccountServiceIntegrationTest {

  private static final String READ_TIMEOUT_MS = "500";

  @RegisterExtension
  static WireMockExtension accountService =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void accountServiceProperties(DynamicPropertyRegistry registry) {
    registry.add("account-service.base-url", accountService::baseUrl);
    registry.add("account-service.read-timeout", () -> READ_TIMEOUT_MS + "ms");
  }

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionRepository transactionRepository;
  @Autowired JwtDecoder jwtDecoder;

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
            .withRequestBody(equalToJson("{\"delta\": 250.00}")));
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
            .withRequestBody(equalToJson("{\"delta\": -40.50}")));
  }

  @Test
  void accountNotFound_returns404_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 404);

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Account not found: " + accountId));

    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountRejectsChange_returns409_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 409);

    createTransaction(accountId, TransactionType.DEBIT, "10.00").andExpect(status().isConflict());

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

    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountServiceError_returns502_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 500);

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isBadGateway());

    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountServiceRejectsServiceToken_returns502() throws Exception {
    UUID accountId = UUID.randomUUID();
    stubAdjustment(accountId, 403);

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isBadGateway());

    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void accountServiceSlowerThanReadTimeout_returns502_andRollsBackInsert() throws Exception {
    UUID accountId = UUID.randomUUID();
    accountService.stubFor(
        post(urlEqualTo(adjustmentPath(accountId)))
            .willReturn(
                aResponse().withStatus(200).withFixedDelay(Integer.parseInt(READ_TIMEOUT_MS) * 4)));

    createTransaction(accountId, TransactionType.CREDIT, "10.00")
        .andExpect(status().isBadGateway());

    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void overPreciseAmount_returns400_withoutCallingAccountService() throws Exception {
    createTransaction(UUID.randomUUID(), TransactionType.CREDIT, "1.00001")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.amount").isNotEmpty());

    assertThat(accountService.getAllServeEvents()).isEmpty();
  }
}

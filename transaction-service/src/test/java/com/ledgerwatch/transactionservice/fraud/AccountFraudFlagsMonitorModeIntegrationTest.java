package com.ledgerwatch.transactionservice.fraud;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.ledgerwatch.transactionservice.fraud.AccountFraudFlagsIntegrationTest.accountAcceptingAdjustments;
import static com.ledgerwatch.transactionservice.fraud.AccountFraudFlagsIntegrationTest.assertSingleFlag;
import static com.ledgerwatch.transactionservice.fraud.AccountFraudFlagsIntegrationTest.flagsRequest;
import static com.ledgerwatch.transactionservice.fraud.AccountFraudFlagsIntegrationTest.stubAccountAged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.event.OutboxPublisher;
import com.ledgerwatch.transactionservice.service.TransactionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** In MONITOR mode a rule hit is still stored as a flag, but the transaction posts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class AccountFraudFlagsMonitorModeIntegrationTest {

  @RegisterExtension
  static WireMockExtension accountService =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("account-service.base-url", accountService::baseUrl);
    registry.add("fraud.mode", () -> "monitor");
  }

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionService transactionService;
  @Autowired OutboxPublisher publisher;

  @Test
  void flagsTheTransactionButPostsIt() throws Exception {
    UUID accountId = accountAcceptingAdjustments(accountService);
    stubAccountAged(accountService, accountId, Duration.ofHours(2));

    Transaction debit =
        transactionService.createTransaction(
            new CreateTransactionRequest(
                accountId, TransactionType.DEBIT, new BigDecimal("1500.00"), null));
    publisher.publishPending();

    String body =
        mockMvc
            .perform(flagsRequest(accountId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Transaction settled = transactionService.getById(debit.getId());
    assertThat(settled.getStatus()).isEqualTo(TransactionStatus.POSTED);
    assertSingleFlag(
        objectMapper.readTree(body).get("content"), settled, NewAccountLargeDebitRule.NAME, false);

    String tx =
        mockMvc
            .perform(
                get("/transactions/{id}", debit.getId())
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode posted = objectMapper.readTree(tx);
    assertThat(posted.get("status").asText()).isEqualTo("POSTED");
    assertThat(posted.get("flags"))
        .extracting(f -> f.get("rule").asText())
        .containsExactly(NewAccountLargeDebitRule.NAME);
  }
}

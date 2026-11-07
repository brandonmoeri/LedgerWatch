package com.ledgerwatch.transactionservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.dto.UpdateTransactionRequest;
import com.ledgerwatch.transactionservice.event.OutboxPublisher;
import com.ledgerwatch.transactionservice.repository.OutboxEventRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class TransactionControllerIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionRepository transactionRepository;
  @Autowired OutboxEventRepository outboxEventRepository;
  @Autowired OutboxPublisher publisher;
  @Autowired PlatformTransactionManager transactionManager;

  // Stubbed to accept every balance change. Failures are covered by
  // TransactionPostingIntegrationTest.
  @MockBean AccountServiceClient accountServiceClient;

  @AfterEach
  void cleanUp() {
    outboxEventRepository.deleteAll();
    transactionRepository.deleteAll();
  }

  private String createPostedTransaction() throws Exception {
    var id = createTransaction();
    publisher.publishPending();
    return id;
  }

  private ResultActions patchTransaction(String id, UpdateTransactionRequest request)
      throws Exception {
    return mockMvc.perform(
        patch("/transactions/{id}", id)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private String createTransaction() throws Exception {
    return createTransaction(
        UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("100.00"), null);
  }

  private String createTransaction(
      UUID accountId, TransactionType type, BigDecimal amount, String description)
      throws Exception {
    var body =
        objectMapper.writeValueAsString(
            new CreateTransactionRequest(accountId, type, amount, description));
    var result =
        mockMvc
            .perform(
                post("/transactions")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void getTransaction_unknownId_returns404() throws Exception {
    mockMvc
        .perform(get("/transactions/{id}", UUID.randomUUID()).with(jwt()))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateTransaction_unknownId_returns404() throws Exception {
    var body =
        objectMapper.writeValueAsString(
            new UpdateTransactionRequest(TransactionStatus.VOIDED, null));
    mockMvc
        .perform(
            patch("/transactions/{id}", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateTransaction_validStatus_returns200() throws Exception {
    var id = createPostedTransaction();
    var body =
        objectMapper.writeValueAsString(
            new UpdateTransactionRequest(TransactionStatus.VOIDED, "cancelled"));
    mockMvc
        .perform(
            patch("/transactions/{id}", id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOIDED"))
        .andExpect(jsonPath("$.description").value("cancelled"));
  }

  @Test
  void getAllTransactions_returnsPagedResultsFilteredByAccountIdAndType() throws Exception {
    var accountId = UUID.randomUUID();
    var creditId =
        createTransaction(accountId, TransactionType.CREDIT, new BigDecimal("25.00"), "salary");
    createTransaction(accountId, TransactionType.DEBIT, new BigDecimal("10.00"), "groceries");
    createTransaction(
        UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("999.00"), "other account");

    mockMvc
        .perform(
            get("/transactions")
                .with(jwt())
                .param("accountId", accountId.toString())
                .param("type", "CREDIT")
                .param("page", "0")
                .param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(creditId))
        .andExpect(jsonPath("$.page.totalElements").value(1));
  }

  @Test
  void getDashboardSummary_returnsBalanceOverTimeAndSpendByType() throws Exception {
    var accountId = UUID.randomUUID();
    createTransaction(accountId, TransactionType.CREDIT, new BigDecimal("200.00"), "salary");
    createTransaction(accountId, TransactionType.DEBIT, new BigDecimal("50.00"), "groceries");
    // different account: must not affect the filtered summary
    createTransaction(
        UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal("999.00"), "other account");
    publisher.publishPending();
    // pending: must not count until it posts
    createTransaction(accountId, TransactionType.CREDIT, new BigDecimal("7.00"), "in flight");

    mockMvc
        .perform(get("/transactions/summary").with(jwt()).param("accountId", accountId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanceOverTime.length()").value(1))
        .andExpect(jsonPath("$.balanceOverTime[0].balance").value(150.00))
        .andExpect(jsonPath("$.spendByType.length()").value(2));
  }

  @Test
  void updateTransaction_alreadyVoided_returns409() throws Exception {
    var id = createPostedTransaction();
    var void1 =
        objectMapper.writeValueAsString(
            new UpdateTransactionRequest(TransactionStatus.VOIDED, null));
    mockMvc
        .perform(
            patch("/transactions/{id}", id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(void1))
        .andExpect(status().isOk());

    // any further mutation on a voided transaction is a conflict
    var void2 =
        objectMapper.writeValueAsString(
            new UpdateTransactionRequest(TransactionStatus.POSTED, null));
    mockMvc
        .perform(
            patch("/transactions/{id}", id)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(void2))
        .andExpect(status().isConflict());
  }

  @Test
  void updateTransaction_cannotSettleOrVoidAPendingTransaction() throws Exception {
    var id = createTransaction();

    patchTransaction(id, new UpdateTransactionRequest(TransactionStatus.POSTED, null))
        .andExpect(status().isConflict());
    patchTransaction(id, new UpdateTransactionRequest(TransactionStatus.REJECTED, null))
        .andExpect(status().isConflict());
    patchTransaction(id, new UpdateTransactionRequest(TransactionStatus.VOIDED, null))
        .andExpect(status().isConflict());

    patchTransaction(id, new UpdateTransactionRequest(null, "memo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.description").value("memo"));
  }

  @Test
  void updateTransaction_rejected_returns409() throws Exception {
    var id = createTransaction();
    doThrow(new NoSuchElementException("Account not found"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any(), any());
    publisher.publishPending();
    reset(accountServiceClient);

    patchTransaction(id, new UpdateTransactionRequest(null, "memo"))
        .andExpect(status().isConflict());
  }

  @Test
  void descriptionEdit_racingWithSettlement_keepsTheSettledStatus() {
    var tx = new Transaction();
    tx.setAccountId(UUID.randomUUID());
    tx.setType(TransactionType.CREDIT);
    tx.setAmount(new BigDecimal("10.00"));
    UUID id = transactionRepository.save(tx).getId();
    var outer = new TransactionTemplate(transactionManager);
    var publisherTransaction = new TransactionTemplate(transactionManager);
    publisherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    // The PATCH reads the row as PENDING, then the publisher commits POSTED before the PATCH does.
    outer.executeWithoutResult(
        status -> {
          var editing = transactionRepository.findById(id).orElseThrow();
          publisherTransaction.executeWithoutResult(
              inner -> transactionRepository.markPosted(id, Instant.now()));
          editing.setDescription("edited");
        });

    var saved = transactionRepository.findById(id).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(TransactionStatus.POSTED);
    assertThat(saved.getDescription()).isEqualTo("edited");
  }
}

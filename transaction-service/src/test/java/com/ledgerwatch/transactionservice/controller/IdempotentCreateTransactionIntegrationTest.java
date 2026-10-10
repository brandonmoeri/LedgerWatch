package com.ledgerwatch.transactionservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.TestcontainersConfiguration;
import com.ledgerwatch.transactionservice.client.AccountServiceClient;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.dto.CreateTransactionRequest;
import com.ledgerwatch.transactionservice.repository.IdempotencyRecordRepository;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
class IdempotentCreateTransactionIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionRepository transactionRepository;
  @Autowired IdempotencyRecordRepository idempotencyRecordRepository;

  @MockBean AccountServiceClient accountServiceClient;

  @AfterEach
  void cleanUp() {
    idempotencyRecordRepository.deleteAll();
    transactionRepository.deleteAll();
  }

  private ResultActions create(String subject, String idempotencyKey, CreateTransactionRequest body)
      throws Exception {
    var request =
        post("/transactions")
            .with(
                jwt()
                    .jwt(j -> j.subject(subject))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body));
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return mockMvc.perform(request);
  }

  private static CreateTransactionRequest credit(String amount, String description) {
    return new CreateTransactionRequest(
        UUID.randomUUID(), TransactionType.CREDIT, new BigDecimal(amount), description);
  }

  private JsonNode body(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void sameKeySameRequest_replaysOriginalResponse_withoutSecondInsertOrBalanceCall()
      throws Exception {
    var request = credit("25.00", "refund");
    String key = UUID.randomUUID().toString();

    MvcResult first =
        create("admin", key, request)
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn();
    MvcResult second =
        create("admin", key, request)
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn();

    assertThat(body(second)).isEqualTo(body(first));
    assertThat(transactionRepository.count()).isEqualTo(1);
    verify(accountServiceClient, times(1)).applyBalanceDelta(any(), any());
  }

  @Test
  void replay_returnsOriginalResponseEvenAfterTransactionIsPatched() throws Exception {
    var request = credit("25.00", "original memo");
    String key = UUID.randomUUID().toString();
    JsonNode original = body(create("admin", key, request).andReturn());

    mockMvc
        .perform(
            patch("/transactions/" + original.get("id").asText())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"edited memo\"}"))
        .andExpect(status().isOk());

    create("admin", key, request)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").value("original memo"));
  }

  @Test
  void sameKeyDifferentRequest_returns422() throws Exception {
    String key = UUID.randomUUID().toString();
    create("admin", key, credit("25.00", null)).andExpect(status().isCreated());

    create("admin", key, credit("99.00", null))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(key)));

    assertThat(transactionRepository.count()).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentCallers_areIndependent() throws Exception {
    String key = UUID.randomUUID().toString();
    var request = credit("25.00", null);

    create("alice", key, request).andExpect(header().doesNotExist("Idempotent-Replayed"));
    create("bob", key, request).andExpect(header().doesNotExist("Idempotent-Replayed"));

    assertThat(transactionRepository.count()).isEqualTo(2);
  }

  @Test
  void failedAttempt_isNotRecorded_soRetryWithSameKeySucceeds() throws Exception {
    var request = credit("25.00", null);
    String key = UUID.randomUUID().toString();
    doThrow(new IllegalStateException("Account is frozen"))
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any());

    create("admin", key, request).andExpect(status().isConflict());
    assertThat(idempotencyRecordRepository.count()).isZero();

    reset(accountServiceClient);
    create("admin", key, request)
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotent-Replayed"));
    assertThat(transactionRepository.count()).isEqualTo(1);
  }

  @Test
  void concurrentDuplicates_createOneTransaction_andAdjustBalanceOnce() throws Exception {
    var request = credit("25.00", null);
    String key = UUID.randomUUID().toString();
    // Hold the first request's DB transaction open so the second arrives while the claim is
    // uncommitted and has to wait on the unique constraint.
    doAnswer(
            inv -> {
              Thread.sleep(500);
              return null;
            })
        .when(accountServiceClient)
        .applyBalanceDelta(any(), any());

    Callable<MvcResult> call = () -> create("admin", key, request).andReturn();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<MvcResult>> futures = pool.invokeAll(List.of(call, call));
      MvcResult a = futures.get(0).get();
      MvcResult b = futures.get(1).get();

      assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus()))
          .containsOnly(201);
      assertThat(body(a)).isEqualTo(body(b));
      assertThat(
              List.of(
                  String.valueOf(a.getResponse().getHeader("Idempotent-Replayed")),
                  String.valueOf(b.getResponse().getHeader("Idempotent-Replayed"))))
          .containsExactlyInAnyOrder("true", "null");
    } finally {
      pool.shutdownNow();
    }
    assertThat(transactionRepository.count()).isEqualTo(1);
    verify(accountServiceClient, times(1)).applyBalanceDelta(any(), any());
  }

  @Test
  void blankKey_returns400() throws Exception {
    create("admin", " ", credit("25.00", null)).andExpect(status().isBadRequest());
    assertThat(transactionRepository.count()).isZero();
  }

  @Test
  void noKey_stillCreatesEachTime() throws Exception {
    var request = credit("25.00", null);

    create("admin", null, request).andExpect(status().isCreated());
    create("admin", null, request).andExpect(status().isCreated());

    assertThat(transactionRepository.count()).isEqualTo(2);
    assertThat(idempotencyRecordRepository.count()).isZero();
  }
}

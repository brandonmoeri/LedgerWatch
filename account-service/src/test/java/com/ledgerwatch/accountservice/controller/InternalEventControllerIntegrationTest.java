package com.ledgerwatch.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ledgerwatch.accountservice.TestcontainersConfiguration;
import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.domain.AccountStatus;
import com.ledgerwatch.accountservice.repository.AccountRepository;
import com.ledgerwatch.accountservice.repository.BalanceAdjustmentRepository;
import com.ledgerwatch.accountservice.repository.ProcessedEventRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
public class InternalEventControllerIntegrationTest {

  private static final RequestPostProcessor SERVICE =
      jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));

  @Autowired MockMvc mockMvc;
  @Autowired AccountRepository accountRepository;
  @Autowired BalanceAdjustmentRepository adjustmentRepository;
  @Autowired ProcessedEventRepository processedEventRepository;

  @AfterEach
  void cleanUp() {
    // deleteAll() skips entities whose isNew() is true, which ProcessedEvent always is.
    processedEventRepository.deleteAllInBatch();
    accountRepository.deleteAll();
  }

  private UUID seed(String balance, AccountStatus status) {
    Account account = new Account();
    account.setOwnerName("Seed");
    account.setBalance(new BigDecimal(balance));
    account.setStatus(status);
    return accountRepository.save(account).getId();
  }

  /** A TransactionCreated envelope shaped like transaction-service's outbox payload. */
  private static String transactionCreated(
      UUID eventId, UUID transactionId, UUID accountId, String type, String amount) {
    return """
        {"eventId": "%s", "eventType": "TransactionCreated",
         "payload": {"transactionId": "%s", "accountId": "%s", "type": "%s", "amount": %s,
                     "description": "coffee", "createdAt": "2026-11-01T12:00:00Z"}}
        """
        .formatted(eventId, transactionId, accountId, type, amount);
  }

  private ResultActions deliver(String json, RequestPostProcessor auth) throws Exception {
    return mockMvc.perform(
        post("/internal/events").with(auth).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private BigDecimal balanceOf(UUID id) {
    return accountRepository.findById(id).orElseThrow().getBalance();
  }

  @Test
  void firstDelivery_appliesChangeAndRecordsEvent() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);
    var eventId = UUID.randomUUID();

    deliver(transactionCreated(eventId, UUID.randomUUID(), id, "DEBIT", "40"), SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(eventId.toString()))
        .andExpect(jsonPath("$.duplicate").value(false));

    assertThat(balanceOf(id)).isEqualByComparingTo("60.00");
    assertThat(processedEventRepository.findById(eventId)).isPresent();
  }

  @Test
  void credit_increasesBalance() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);

    deliver(
            transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "CREDIT", "25.1234"),
            SERVICE)
        .andExpect(status().isOk());

    assertThat(balanceOf(id)).isEqualByComparingTo("125.1234");
  }

  @Test
  void redelivery_isAcknowledgedAsDuplicateAndAppliedOnce() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);
    String json = transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "DEBIT", "40");

    deliver(json, SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(false));
    deliver(json, SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));
    deliver(json, SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));

    assertThat(balanceOf(id)).isEqualByComparingTo("60.00");
    assertThat(processedEventRepository.count()).isEqualTo(1);
    assertThat(adjustmentRepository.count()).isEqualTo(1);
  }

  @Test
  void redelivery_afterAccountFrozen_isStillAcknowledged() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);
    String json = transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "DEBIT", "40");
    deliver(json, SERVICE).andExpect(status().isOk());
    var account = accountRepository.findById(id).orElseThrow();
    account.setStatus(AccountStatus.FROZEN);
    accountRepository.save(account);

    deliver(json, SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));

    assertThat(balanceOf(id)).isEqualByComparingTo("60.00");
  }

  @Test
  void eventForTransactionAlreadyAppliedSynchronously_doesNotApplyItAgain() throws Exception {
    // POST /transactions applies the change itself with adjustmentId = transaction id before the
    // outbox event is ever published.
    var id = seed("100.00", AccountStatus.ACTIVE);
    var transactionId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/internal/accounts/{id}/balance-adjustments", id)
                .with(SERVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adjustmentId\": \"" + transactionId + "\", \"delta\": -40}"))
        .andExpect(status().isOk());
    var eventId = UUID.randomUUID();

    deliver(transactionCreated(eventId, transactionId, id, "DEBIT", "40"), SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(false));

    assertThat(balanceOf(id)).isEqualByComparingTo("60.00");
    assertThat(processedEventRepository.findById(eventId)).isPresent();
  }

  @Test
  void distinctEventsForSameTransaction_applyChangeOnce() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);
    var transactionId = UUID.randomUUID();

    deliver(transactionCreated(UUID.randomUUID(), transactionId, id, "DEBIT", "40"), SERVICE)
        .andExpect(status().isOk());
    deliver(transactionCreated(UUID.randomUUID(), transactionId, id, "DEBIT", "40"), SERVICE)
        .andExpect(status().isOk());

    assertThat(balanceOf(id)).isEqualByComparingTo("60.00");
  }

  @Test
  void concurrentRedeliveries_applyChangeOnce() throws Exception {
    var id = seed("100.00", AccountStatus.ACTIVE);
    String json = transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "DEBIT", "10");
    int threads = 8;
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(threads);
    List<MockHttpServletResponse> responses = new ArrayList<>();
    try {
      List<Future<MockHttpServletResponse>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<MockHttpServletResponse> call =
            () -> {
              start.await();
              return deliver(json, SERVICE).andReturn().getResponse();
            };
        results.add(pool.submit(call));
      }
      start.countDown();
      for (Future<MockHttpServletResponse> result : results) {
        responses.add(result.get());
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
    long applied =
        responses.stream().filter(r -> uncheckedBody(r).contains("\"duplicate\":false")).count();
    assertThat(applied).isEqualTo(1);
    assertThat(balanceOf(id)).isEqualByComparingTo("90.00");
    assertThat(processedEventRepository.count()).isEqualTo(1);
  }

  private static String uncheckedBody(MockHttpServletResponse response) {
    try {
      return response.getContentAsString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void rejectedEvent_isNotRecorded_soRedeliveryCanSucceed() throws Exception {
    var id = seed("10.00", AccountStatus.ACTIVE);
    String json = transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "DEBIT", "40");

    deliver(json, SERVICE)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("urn:ledgerwatch:problem:insufficient-funds"));
    assertThat(processedEventRepository.count()).isZero();

    deliver(transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "CREDIT", "50"), SERVICE)
        .andExpect(status().isOk());
    deliver(json, SERVICE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(false));

    assertThat(balanceOf(id)).isEqualByComparingTo("20.00");
  }

  @Test
  void frozenAccount_returns409AndRecordsNothing() throws Exception {
    var id = seed("100.00", AccountStatus.FROZEN);

    deliver(transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "CREDIT", "10"), SERVICE)
        .andExpect(status().isConflict());

    assertThat(balanceOf(id)).isEqualByComparingTo("100.00");
    assertThat(processedEventRepository.count()).isZero();
  }

  @Test
  void unknownAccount_returns404() throws Exception {
    deliver(
            transactionCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CREDIT", "10"),
            SERVICE)
        .andExpect(status().isNotFound());

    assertThat(processedEventRepository.count()).isZero();
  }

  @Test
  void unsupportedEventType_returns400AndRecordsNothing() throws Exception {
    String json =
        "{\"eventId\": \""
            + UUID.randomUUID()
            + "\", \"eventType\": \"AccountOpened\", \"payload\": {}}";

    deliver(json, SERVICE).andExpect(status().isBadRequest());

    assertThat(processedEventRepository.count()).isZero();
  }

  @Test
  void missingEnvelopeFields_return400() throws Exception {
    deliver("{\"payload\": {}}", SERVICE)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.eventId").exists())
        .andExpect(jsonPath("$.errors.eventType").exists());
  }

  @Test
  void payloadMissingAmount_returns400() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);
    String json =
        """
        {"eventId": "%s", "eventType": "TransactionCreated",
         "payload": {"transactionId": "%s", "accountId": "%s", "type": "CREDIT"}}
        """
            .formatted(UUID.randomUUID(), UUID.randomUUID(), id);

    deliver(json, SERVICE).andExpect(status().isBadRequest());
  }

  @Test
  void payloadWithUnknownTransactionType_returns400() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    deliver(transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "REFUND", "10"), SERVICE)
        .andExpect(status().isBadRequest());
  }

  @Test
  void payloadWithNonPositiveAmount_returns400() throws Exception {
    var id = seed("100", AccountStatus.ACTIVE);

    deliver(transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "DEBIT", "-10"), SERVICE)
        .andExpect(status().isBadRequest());
    deliver(transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "DEBIT", "0"), SERVICE)
        .andExpect(status().isBadRequest());

    assertThat(balanceOf(id)).isEqualByComparingTo("100");
  }

  @Test
  void payloadWithMoreThanFourDecimals_returns400() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    deliver(
            transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "CREDIT", "1.00001"),
            SERVICE)
        .andExpect(status().isBadRequest());
  }

  @Test
  void adminRole_returns403() throws Exception {
    var id = seed("0", AccountStatus.ACTIVE);

    deliver(
            transactionCreated(UUID.randomUUID(), UUID.randomUUID(), id, "CREDIT", "1"),
            jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void noToken_returns401() throws Exception {
    mockMvc
        .perform(
            post("/internal/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    transactionCreated(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CREDIT", "1")))
        .andExpect(status().isUnauthorized());
  }
}

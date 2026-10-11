package com.ledgerwatch.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ledgerwatch.accountservice.TestcontainersConfiguration;
import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Fires 50 simultaneous DEBITs (negative balance deltas, as transaction-service sends them) at one
 * account in a real Postgres. Optimistic-lock conflicts are retried server-side, so every debit
 * either applies exactly once or is rejected for a business reason — none are lost or surface as
 * 409.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
public class ConcurrentDebitIntegrationTest {

  private static final int DEBITS = 50;
  private static final RequestPostProcessor SERVICE =
      jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));

  @Autowired MockMvc mockMvc;
  @Autowired AccountRepository accountRepository;

  @AfterEach
  void cleanUp() {
    accountRepository.deleteAll();
  }

  private UUID seed(String balance) {
    Account account = new Account();
    account.setOwnerName("Concurrent Debit");
    account.setBalance(new BigDecimal(balance));
    return accountRepository.save(account).getId();
  }

  @Test
  void fiftyParallelDebits_eachAppliedExactlyOnce() throws Exception {
    UUID id = seed("1000.00");

    Map<Integer, Long> statuses = debitConcurrently(id, "-10.00");

    assertThat(statuses).containsExactly(Map.entry(200, (long) DEBITS));
    Account after = accountRepository.findById(id).orElseThrow();
    assertThat(after.getBalance()).isEqualByComparingTo("500.00"); // 1000 - 50 * 10
    assertThat(after.getVersion()).isEqualTo(DEBITS); // one committed write per debit
  }

  @Test
  void fiftyParallelDebits_neverOverdrawTheAccount() throws Exception {
    UUID id = seed("250.00"); // funds for exactly 25 of the 50 debits

    Map<Integer, Long> statuses = debitConcurrently(id, "-10.00");

    assertThat(statuses).containsOnly(Map.entry(200, 25L), Map.entry(422, 25L));
    Account after = accountRepository.findById(id).orElseThrow();
    assertThat(after.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(after.getVersion()).isEqualTo(25L);
  }

  /** Releases {@link #DEBITS} requests at once and returns a count per HTTP status. */
  private Map<Integer, Long> debitConcurrently(UUID id, String delta) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(DEBITS);
    CountDownLatch ready = new CountDownLatch(DEBITS);
    CountDownLatch start = new CountDownLatch(1);
    String body = "{\"delta\": " + delta + "}";
    Callable<Integer> debit =
        () -> {
          ready.countDown();
          start.await();
          return mockMvc
              .perform(
                  post("/internal/accounts/{id}/balance-adjustments", id)
                      .with(SERVICE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andReturn()
              .getResponse()
              .getStatus();
        };
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < DEBITS; i++) {
        results.add(pool.submit(debit));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : results) {
        statuses.add(f.get(60, TimeUnit.SECONDS));
      }
      return statuses.stream()
          .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    } finally {
      pool.shutdownNow();
    }
  }
}

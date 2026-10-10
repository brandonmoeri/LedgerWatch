package com.ledgerwatch.accountservice.service;

import static org.assertj.core.api.Assertions.*;

import com.ledgerwatch.accountservice.TestcontainersConfiguration;
import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

/** Exercises {@code @Version} against a real Postgres to prove lost updates are prevented. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SuppressWarnings("null")
public class AccountOptimisticLockingIntegrationTest {

  @Autowired AccountService accountService;
  @Autowired AccountRepository accountRepository;

  @AfterEach
  void cleanUp() {
    accountRepository.deleteAll();
  }

  private UUID seed(String balance) {
    Account account = new Account();
    account.setOwnerName("Lock Test");
    account.setBalance(new BigDecimal(balance));
    return accountRepository.save(account).getId();
  }

  @Test
  void staleWrite_afterBalanceDelta_isRejected() {
    UUID id = seed("100.00");
    // Detached copy read before the delta is applied (no surrounding transaction).
    Account stale = accountRepository.findById(id).orElseThrow();

    accountService.applyBalanceDelta(id, new BigDecimal("10"));

    stale.setBalance(new BigDecimal("999"));
    assertThatThrownBy(() -> accountRepository.save(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(accountRepository.findById(id).orElseThrow().getBalance())
        .isEqualByComparingTo("110.00");
  }

  @Test
  void concurrentDeltas_areRetriedAndNeverLost() throws Exception {
    UUID id = seed("0");
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    accountService.applyBalanceDelta(id, BigDecimal.ONE);
                    return true;
                  } catch (ObjectOptimisticLockingFailureException e) {
                    return false;
                  }
                }));
      }
      start.countDown();

      int succeeded = 0;
      for (Future<Boolean> f : results) {
        if (f.get(30, TimeUnit.SECONDS)) {
          succeeded++;
        }
      }

      Account after = accountRepository.findById(id).orElseThrow();
      // Conflicts are retried, so every delta lands, and lands exactly once.
      assertThat(succeeded).isEqualTo(threads);
      assertThat(after.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(threads));
      assertThat(after.getVersion()).isEqualTo((long) threads);
    } finally {
      pool.shutdownNow();
    }
  }
}

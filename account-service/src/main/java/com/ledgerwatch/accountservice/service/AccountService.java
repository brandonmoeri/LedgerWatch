package com.ledgerwatch.accountservice.service;

import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.domain.AccountStatus;
import com.ledgerwatch.accountservice.domain.BalanceAdjustment;
import com.ledgerwatch.accountservice.dto.CreateAccountRequest;
import com.ledgerwatch.accountservice.dto.UpdateAccountRequest;
import com.ledgerwatch.accountservice.repository.AccountRepository;
import com.ledgerwatch.accountservice.repository.AccountSpecifications;
import com.ledgerwatch.accountservice.repository.BalanceAdjustmentRepository;
import com.ledgerwatch.common.error.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
@Transactional(readOnly = true)
@EnableConfigurationProperties(BalanceRetryProperties.class)
public class AccountService {
  private final AccountRepository repo;
  private final BalanceAdjustmentRepository adjustmentRepo;
  private final TransactionOperations tx;
  private final BalanceRetryProperties retry;

  public AccountService(
      AccountRepository repo,
      BalanceAdjustmentRepository adjustmentRepo,
      TransactionOperations tx,
      BalanceRetryProperties retry) {
    this.repo = repo;
    this.adjustmentRepo = adjustmentRepo;
    this.tx = tx;
    this.retry = retry;
  }

  public Account getById(UUID id) {
    Objects.requireNonNull(id, "Account id must not be null");
    return repo.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Account not found: " + id));
  }

  public Page<Account> getAll(AccountStatus status, String ownerName, Pageable pageable) {
    return repo.findAll(AccountSpecifications.filter(status, ownerName), pageable);
  }

  @Transactional
  public Account createAccount(CreateAccountRequest request) {
    Account account = new Account();
    account.setOwnerName(request.ownerName());
    if (request.initialBalance() != null) {
      if (request.initialBalance().compareTo(BigDecimal.ZERO) < 0) {
        throw new IllegalArgumentException("Initial balance cannot be negative");
      }
      account.setBalance(request.initialBalance());
    }
    return repo.save(account);
  }

  @Transactional
  public Account updateAccount(UUID id, UpdateAccountRequest request) {
    Account account = Objects.requireNonNull(getById(id), "getById returned null");
    if (AccountStatus.CLOSED.equals(account.getStatus())) {
      throw new IllegalStateException("Account is closed and cannot be modified: " + id);
    }
    if (request.ownerName() != null && !request.ownerName().isBlank()) {
      account.setOwnerName(request.ownerName());
    }
    if (request.status() != null) {
      account.setStatus(request.status());
    }
    return Objects.requireNonNull(repo.save(account), "Repository returned null for account");
  }

  /**
   * Applies {@code delta} without deduplication. See {@link #applyBalanceDelta(UUID, BigDecimal,
   * UUID)}.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Account applyBalanceDelta(UUID id, BigDecimal delta) {
    return applyBalanceDelta(id, delta, null);
  }

  /**
   * Adds {@code delta} to the account balance. A negative delta (a debit) that would take the
   * balance below zero throws {@link InsufficientFundsException}.
   *
   * <p>If {@code adjustmentId} is given, the adjustment is applied at most once: the id is recorded
   * in the same transaction as the balance change, and a repeat returns the account unchanged (even
   * if it has since been frozen or closed). Reusing an id for a different account or delta throws
   * {@link IllegalStateException} (HTTP 409). This is what makes the caller's retries safe when a
   * response is lost after the change committed.
   *
   * <p>Concurrent writers are detected by the {@code @Version} column. Each attempt runs in its own
   * transaction; an attempt that loses the race is rolled back and retried against a fresh read, up
   * to {@link BalanceRetryProperties#maxAttempts()} times with jittered exponential backoff. If
   * every attempt conflicts, the last {@link OptimisticLockingFailureException} propagates (HTTP
   * 409). Two concurrent requests with the same {@code adjustmentId} serialize the same way: the
   * loser's retry sees the winner's record and replays.
   *
   * <p>Runs outside any caller transaction ({@code NOT_SUPPORTED}): a retry inside a transaction
   * that already hit a conflict would reuse its rolled-back state and stale persistence context.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Account applyBalanceDelta(UUID id, BigDecimal delta, UUID adjustmentId) {
    Objects.requireNonNull(delta, "Delta must not be null");
    if (delta.signum() == 0) {
      throw new IllegalArgumentException("Delta must be non-zero");
    }
    for (int attempt = 1; ; attempt++) {
      try {
        return tx.execute(status -> applyBalanceDeltaOnce(id, delta, adjustmentId));
      } catch (OptimisticLockingFailureException e) {
        if (attempt >= retry.maxAttempts()) {
          throw e;
        }
        backOff(attempt, e);
      }
    }
  }

  private Account applyBalanceDeltaOnce(UUID id, BigDecimal delta, UUID adjustmentId) {
    if (adjustmentId != null) {
      var applied = adjustmentRepo.findById(adjustmentId);
      if (applied.isPresent()) {
        return replay(applied.get(), id, delta);
      }
    }
    Account account = getById(id);
    if (account.getStatus() != AccountStatus.ACTIVE) {
      throw new IllegalStateException(
          "Account is " + account.getStatus() + " and cannot accept balance changes: " + id);
    }
    BigDecimal newBalance = account.getBalance().add(delta);
    // Only debits are checked, so a credit is accepted even if the balance is already negative.
    // The check reads a versioned row: if another writer changes the balance first, the flush
    // below fails with an optimistic-lock conflict and the whole attempt is retried.
    if (delta.signum() < 0 && newBalance.signum() < 0) {
      throw new InsufficientFundsException(id, delta.negate());
    }
    account.setBalance(newBalance);
    // Flush now so a version conflict surfaces here rather than at commit.
    Account saved = repo.saveAndFlush(account);
    if (adjustmentId != null) {
      adjustmentRepo.saveAndFlush(new BalanceAdjustment(adjustmentId, id, delta));
    }
    return saved;
  }

  private Account replay(BalanceAdjustment applied, UUID id, BigDecimal delta) {
    if (!applied.getAccountId().equals(id) || applied.getDelta().compareTo(delta) != 0) {
      throw new IllegalStateException(
          "Balance adjustment " + applied.getId() + " was already applied with different values");
    }
    return getById(id);
  }

  private void backOff(int attempt, OptimisticLockingFailureException cause) {
    long ceiling =
        Math.min(
            retry.maxBackoff().toMillis(),
            retry.initialBackoff().toMillis() << Math.min(attempt - 1, 20));
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling + 1));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw cause;
    }
  }
}

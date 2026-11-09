package com.ledgerwatch.transactionservice.fraud;

import com.ledgerwatch.transactionservice.client.AccountSummary;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The transaction being screened, plus a lazy, memoized lookup of its account. Only rules that need
 * account details (such as its age) trigger the call to account-service, and at most once per
 * screening. Not thread-safe; each screening gets its own context.
 */
public final class FraudContext {

  private final UUID transactionId;
  private final UUID accountId;
  private final TransactionType type;
  private final BigDecimal amount;
  private final Instant createdAt;
  private final Supplier<AccountSummary> accountLookup;
  private AccountSummary account;

  public FraudContext(
      UUID transactionId,
      UUID accountId,
      TransactionType type,
      BigDecimal amount,
      Instant createdAt,
      Supplier<AccountSummary> accountLookup) {
    this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.type = Objects.requireNonNull(type, "type");
    this.amount = Objects.requireNonNull(amount, "amount");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.accountLookup = Objects.requireNonNull(accountLookup, "accountLookup");
  }

  public UUID transactionId() {
    return transactionId;
  }

  public UUID accountId() {
    return accountId;
  }

  public TransactionType type() {
    return type;
  }

  public BigDecimal amount() {
    return amount;
  }

  /** When the transaction was created; rules measure their windows back from here. */
  public Instant createdAt() {
    return createdAt;
  }

  /** The account, fetched from account-service on first use. */
  public AccountSummary account() {
    if (account == null) {
      account = Objects.requireNonNull(accountLookup.get(), "account lookup returned null");
    }
    return account;
  }
}

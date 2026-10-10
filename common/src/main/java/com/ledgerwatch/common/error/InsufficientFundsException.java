package com.ledgerwatch.common.error;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

/**
 * A debit would take an account's balance below zero. Thrown by account-service, which owns the
 * balance, and re-thrown by callers that receive its 422, so every service reports it with the same
 * problem {@link #TYPE}.
 */
public class InsufficientFundsException extends RuntimeException {

  /** RFC 7807 {@code type} that lets clients tell this 422 apart from other 422s. */
  public static final URI TYPE = URI.create("urn:ledgerwatch:problem:insufficient-funds");

  private final UUID accountId;
  private final BigDecimal amount;

  public InsufficientFundsException(UUID accountId, BigDecimal amount) {
    super(
        "Account "
            + accountId
            + " has insufficient funds for a debit of "
            + amount.toPlainString());
    this.accountId = accountId;
    this.amount = amount;
  }

  public UUID getAccountId() {
    return accountId;
  }

  /** The debit amount that was rejected, as a positive number. */
  public BigDecimal getAmount() {
    return amount;
  }
}

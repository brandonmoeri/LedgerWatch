package com.ledgerwatch.common.error;

import java.net.URI;
import java.util.UUID;

/**
 * An account is FROZEN or CLOSED, so it cannot accept a balance change. Thrown by account-service,
 * which owns the account, and re-thrown by callers that receive its 409. The problem {@link #TYPE}
 * tells this final answer apart from other 409s, such as a concurrent modification that may succeed
 * if retried.
 */
public class AccountNotActiveException extends IllegalStateException {

  public static final URI TYPE = URI.create("urn:ledgerwatch:problem:account-not-active");

  private final UUID accountId;
  private final String accountStatus;

  public AccountNotActiveException(UUID accountId, String accountStatus) {
    super("Account is " + accountStatus + " and cannot accept balance changes: " + accountId);
    this.accountId = accountId;
    this.accountStatus = accountStatus;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getAccountStatus() {
    return accountStatus;
  }
}

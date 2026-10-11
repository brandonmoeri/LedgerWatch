package com.ledgerwatch.transactionservice.client;

/**
 * account-service could not be reached, timed out, or answered 5xx: a failure that may clear up on
 * its own. These, and only these, are retried and counted by the {@code accountService} circuit
 * breaker. Mapped to 502 Bad Gateway like its parent.
 */
public class AccountServiceUnavailableException extends AccountServiceException {

  public AccountServiceUnavailableException(String message) {
    super(message);
  }

  public AccountServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

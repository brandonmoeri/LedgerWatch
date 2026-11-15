package com.ledgerwatch.insightsservice.client;

/**
 * account-service or transaction-service refused the call, failed, or couldn't be reached.
 *
 * <p>The message is handed to Claude as the tool's result, so it says what went wrong in plain
 * terms and never includes the caller's token or the downstream response body.
 */
public class LedgerServiceException extends RuntimeException {

  public LedgerServiceException(String message) {
    super(message);
  }

  public LedgerServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}

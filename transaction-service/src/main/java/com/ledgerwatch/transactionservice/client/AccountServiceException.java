package com.ledgerwatch.transactionservice.client;

/**
 * account-service could not be reached, timed out, or answered in a way this service cannot act on
 * (5xx, auth failure, unexpected 4xx). Mapped to 502 Bad Gateway.
 */
public class AccountServiceException extends RuntimeException {

  public AccountServiceException(String message) {
    super(message);
  }

  public AccountServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}

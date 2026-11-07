package com.ledgerwatch.transactionservice.client;

/**
 * account-service answered 409 for a reason other than the account's status, typically a concurrent
 * modification that outlasted its own retries. The request itself may be fine, so it is worth
 * trying again later, but account-service is healthy: this doesn't count against the circuit
 * breaker.
 */
public class AccountServiceConflictException extends AccountServiceException {

  public AccountServiceConflictException(String message) {
    super(message);
  }
}

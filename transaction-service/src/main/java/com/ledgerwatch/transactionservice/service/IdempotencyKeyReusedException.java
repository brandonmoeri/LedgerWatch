package com.ledgerwatch.transactionservice.service;

/** An {@code Idempotency-Key} was reused with a different request payload. */
public class IdempotencyKeyReusedException extends RuntimeException {
  public IdempotencyKeyReusedException(String idempotencyKey) {
    super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request");
  }
}

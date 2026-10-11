package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.common.error.AbstractApiExceptionHandler;
import com.ledgerwatch.transactionservice.client.AccountServiceException;
import com.ledgerwatch.transactionservice.service.IdempotencyKeyReusedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(AccountServiceException.class)
  public ProblemDetail handleAccountServiceFailure(AccountServiceException ex) {
    log.warn("Call to account-service failed", ex);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_GATEWAY, "Account service is unavailable; try again later");
  }

  /** The breaker is open, so the call was refused locally; account-service saw nothing. */
  @ExceptionHandler(CallNotPermittedException.class)
  public ProblemDetail handleCircuitOpen(CallNotPermittedException ex) {
    log.debug("Call to account-service short-circuited: {}", ex.getMessage());
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "Account service is unavailable; try again later");
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
  }
}

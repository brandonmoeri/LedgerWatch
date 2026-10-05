package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.common.error.AbstractApiExceptionHandler;
import com.ledgerwatch.transactionservice.client.AccountServiceException;
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
}

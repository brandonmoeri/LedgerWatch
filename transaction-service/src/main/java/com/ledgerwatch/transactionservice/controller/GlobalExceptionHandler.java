package com.ledgerwatch.transactionservice.controller;

import com.ledgerwatch.common.error.AbstractApiExceptionHandler;
import com.ledgerwatch.transactionservice.service.IdempotencyKeyReusedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
  }
}

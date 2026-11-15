package com.ledgerwatch.insightsservice.controller;

import com.ledgerwatch.common.error.AbstractApiExceptionHandler;
import com.ledgerwatch.insightsservice.ai.InsightsGenerationException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Failures talking to Claude become generic problem details. The Anthropic error body is never
 * passed through; InsightsChat has already logged the request with its outcome.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

  @ExceptionHandler(InsightsGenerationException.class)
  public ProblemDetail handleGeneration(InsightsGenerationException ex) {
    if ("refusal".equals(ex.getStopReason())) {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.UNPROCESSABLE_ENTITY, "The assistant can't help with that question.");
      problem.setTitle("Question declined");
      return problem;
    }
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY,
            "The assistant couldn't produce a complete answer. Try a narrower question.");
    problem.setTitle("No complete answer");
    return problem;
  }

  /** 429, 5xx or a network failure from the Anthropic API, after Spring AI's retries. */
  @ExceptionHandler(TransientAiException.class)
  public ProblemDetail handleTransient(TransientAiException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The insights assistant is temporarily unavailable. Try again shortly.");
    problem.setTitle("Assistant unavailable");
    return problem;
  }

  /** A 4xx from the Anthropic API: a configuration or request problem on our side. */
  @ExceptionHandler(NonTransientAiException.class)
  public ProblemDetail handleNonTransient(NonTransientAiException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY, "The insights assistant failed to answer.");
    problem.setTitle("Assistant error");
    return problem;
  }
}

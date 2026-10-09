package com.ledgerwatch.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class AbstractApiExceptionHandlerTest {

  private final AbstractApiExceptionHandler handler = new AbstractApiExceptionHandler() {};

  @Test
  void insufficientFunds_mapsTo422WithTypedProblem() {
    UUID accountId = UUID.randomUUID();

    ProblemDetail problem =
        handler.handleInsufficientFunds(
            new InsufficientFundsException(accountId, new BigDecimal("40.50")));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    assertThat(problem.getType()).isEqualTo(InsufficientFundsException.TYPE);
    assertThat(problem.getTitle()).isEqualTo("Insufficient funds");
    assertThat(problem.getDetail())
        .isEqualTo("Account " + accountId + " has insufficient funds for a debit of 40.50");
    assertThat(problem.getProperties())
        .containsEntry("accountId", accountId)
        .containsEntry("amount", new BigDecimal("40.50"));
  }

  @Test
  void notFound_mapsTo404() {
    ProblemDetail problem = handler.handleNotFound(new NoSuchElementException("gone"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getDetail()).isEqualTo("gone");
  }

  @Test
  void illegalState_mapsTo409() {
    ProblemDetail problem = handler.handleConflict(new IllegalStateException("frozen"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).isEqualTo("frozen");
  }

  @Test
  void illegalArgument_mapsTo400() {
    ProblemDetail problem = handler.handleBadRequest(new IllegalArgumentException("bad"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("bad");
  }

  @Test
  void validationFailure_mapsTo400WithFieldErrors() throws Exception {
    BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "req");
    bindingResult.addError(new FieldError("req", "amount", "amount is required"));
    MethodParameter parameter =
        new MethodParameter(Object.class.getMethod("equals", Object.class), 0);

    ProblemDetail problem =
        handler.handleValidation(new MethodArgumentNotValidException(parameter, bindingResult));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getProperties())
        .containsEntry("errors", Map.of("amount", "amount is required"));
  }
}

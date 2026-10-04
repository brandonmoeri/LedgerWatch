package com.ledgerwatch.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerwatch.accountservice.domain.Account;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class GlobalExceptionHandlerTest {

  @Test
  void optimisticLockFailure_mapsTo409() {
    ProblemDetail problem =
        new GlobalExceptionHandler()
            .handleOptimisticLock(
                new ObjectOptimisticLockingFailureException(Account.class, UUID.randomUUID()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getDetail()).contains("modified concurrently");
  }
}

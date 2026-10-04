package com.ledgerwatch.accountservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record BalanceAdjustmentRequest(
    @NotNull(message = "delta is required")
        @Digits(
            integer = 15,
            fraction = 4,
            message = "delta must have at most 15 integer digits and 4 decimal places")
        BigDecimal delta) {}

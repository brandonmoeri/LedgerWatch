package com.ledgerwatch.accountservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record BalanceAdjustmentRequest(
    @Schema(
            description =
                "Optional caller-chosen id. An adjustment with an id is applied at most once;"
                    + " transaction-service sends its transaction id.")
        UUID adjustmentId,
    @NotNull(message = "delta is required")
        @Digits(
            integer = 15,
            fraction = 4,
            message = "delta must have at most 15 integer digits and 4 decimal places")
        BigDecimal delta) {}

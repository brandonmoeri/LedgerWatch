package com.ledgerwatch.transactionservice.dto;

import com.ledgerwatch.transactionservice.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransactionRequest(
    @NotNull UUID accountId,
    @NotNull TransactionType type,
    // Matches NUMERIC(19,4) here and account-service's balance-adjustment delta limits.
    @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(
            integer = 15,
            fraction = 4,
            message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,
    @Size(max = 255) String description) {}

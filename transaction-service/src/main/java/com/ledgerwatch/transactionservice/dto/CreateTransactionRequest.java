package com.ledgerwatch.transactionservice.dto;

import com.ledgerwatch.transactionservice.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransactionRequest (
    @NotNull UUID accountId,
    @NotNull TransactionType type,
    @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero") BigDecimal amount,
    @Size(max = 255) String description
) {}

package com.ledgerwatch.transactionservice.dto;

import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import jakarta.validation.constraints.Size;

public record UpdateTransactionRequest(
    TransactionStatus status,
    @Size(max = 255) String description
) {}

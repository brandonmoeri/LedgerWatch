package com.ledgerwatch.transactionservice.dto;

import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse (UUID id, UUID accountId, TransactionType type, TransactionStatus status,
    BigDecimal amount, String description, Instant createdAt, Instant updatedAt
) {
    public static TransactionResponse from (Transaction t) {
        return new TransactionResponse(
            t.getId(), t.getAccountId(), t.getType(), t.getStatus(),
            t.getAmount(), t.getDescription(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}

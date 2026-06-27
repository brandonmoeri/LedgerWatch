package com.ledgerwatch.accountservice.dto;

import com.ledgerwatch.accountservice.domain.Account;
import com.ledgerwatch.accountservice.domain.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse (UUID id, String ownerName, AccountStatus status, BigDecimal balance, Instant createdAt, Instant updatedAt) {
    public static AccountResponse from (Account account) {
        return new AccountResponse(
            account.getId(),
            account.getOwnerName(),
            account.getStatus(),
            account.getBalance(),
            account.getCreatedAt(),
            account.getUpdatedAt()
        );
    }
}

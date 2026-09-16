package com.ledgerwatch.accountservice.dto;

import com.ledgerwatch.accountservice.domain.AccountStatus;
import jakarta.validation.constraints.Size;

public record UpdateAccountRequest(
    @Size(min = 1, max = 255, message = "ownerName must be between 1 and 255 characters")
        String ownerName,
    AccountStatus status) {}

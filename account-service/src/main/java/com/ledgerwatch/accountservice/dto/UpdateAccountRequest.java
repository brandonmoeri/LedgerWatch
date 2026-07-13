package com.ledgerwatch.accountservice.dto;

import jakarta.validation.constraints.Size;
import com.ledgerwatch.accountservice.domain.AccountStatus;

public record UpdateAccountRequest (
    @Size(min = 1, max = 255, message = "ownerName must be between 1 and 255 characters")
    String ownerName, 
    AccountStatus status
) {}

package com.ledgerwatch.accountservice.dto;

import com.ledgerwatch.accountservice.domain.AccountStatus;

public record UpdateAccountRequest (String ownerName, AccountStatus status) {
    
}

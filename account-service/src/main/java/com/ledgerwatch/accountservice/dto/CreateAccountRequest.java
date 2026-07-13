package com.ledgerwatch.accountservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record CreateAccountRequest (
    @NotBlank String ownerName, 
    @DecimalMin(value = "0.00", message = "Initial balance must be non-negative")
    BigDecimal initialBalance
) {}

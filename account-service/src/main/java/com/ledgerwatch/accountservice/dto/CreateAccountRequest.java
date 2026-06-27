package com.ledgerwatch.accountservice.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record CreateAccountRequest (@NotBlank String ownerName, BigDecimal initialBalance) {

}

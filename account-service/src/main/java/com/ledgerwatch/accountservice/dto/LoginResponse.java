package com.ledgerwatch.accountservice.dto;

public record LoginResponse(String token, String tokenType, long expiresIn) {
}

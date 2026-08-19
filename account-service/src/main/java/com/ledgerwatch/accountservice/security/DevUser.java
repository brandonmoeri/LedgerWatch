package com.ledgerwatch.accountservice.security;

import java.util.List;

public record DevUser(String username, String passwordHash, List<String> roles) {
}

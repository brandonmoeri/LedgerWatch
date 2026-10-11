package com.ledgerwatch.accountservice.service;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Retry policy for balance adjustments that lose an optimistic-lock race ({@code balance-retry.*}).
 * Attempt {@code n} waits a random time up to {@code min(maxBackoff, initialBackoff * 2^(n-1))}
 * ("full jitter") so contending writers spread out instead of colliding again in lockstep.
 */
@Validated
@ConfigurationProperties("balance-retry")
public record BalanceRetryProperties(
    @DefaultValue("10") @Min(1) int maxAttempts,
    @DefaultValue("5ms") @NotNull Duration initialBackoff,
    @DefaultValue("200ms") @NotNull Duration maxBackoff) {}

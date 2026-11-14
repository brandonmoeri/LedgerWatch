package com.ledgerwatch.transactionservice.fraud;

/**
 * A rule that fired.
 *
 * @param rule the {@link FraudRule#name()} that produced it
 * @param detail human-readable reason, e.g. {@code "12 transactions in PT10M (max 10)"}
 */
public record FraudSignal(String rule, String detail) {}

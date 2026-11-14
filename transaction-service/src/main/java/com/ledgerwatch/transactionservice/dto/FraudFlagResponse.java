package com.ledgerwatch.transactionservice.dto;

import com.ledgerwatch.transactionservice.domain.FraudFlag;
import java.time.Instant;
import java.util.UUID;

/**
 * A fraud rule that fired on one of the account's transactions.
 *
 * @param blocked true if the transaction was rejected as SUSPECTED_FRAUD; false if it was posted
 *     anyway because the rules ran in MONITOR mode
 */
public record FraudFlagResponse(
    UUID id,
    UUID transactionId,
    UUID accountId,
    String rule,
    String detail,
    boolean blocked,
    Instant flaggedAt) {
  public static FraudFlagResponse from(FraudFlag f) {
    return new FraudFlagResponse(
        f.getId(),
        f.getTransactionId(),
        f.getAccountId(),
        f.getRule(),
        f.getDetail(),
        f.isBlocked(),
        f.getFlaggedAt());
  }
}

package com.ledgerwatch.transactionservice.dto;

import com.ledgerwatch.transactionservice.domain.FraudFlag;
import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionRejectionReason;
import com.ledgerwatch.transactionservice.domain.TransactionStatus;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param flags the fraud rules that fired on this transaction, by rule name; empty if none did or
 *     it hasn't been screened yet (PENDING). Whether they blocked it shows in {@code status} and
 *     {@code rejectionReason}: REJECTED as SUSPECTED_FRAUD, or POSTED in MONITOR mode.
 */
public record TransactionResponse(
    UUID id,
    UUID accountId,
    TransactionType type,
    TransactionStatus status,
    TransactionRejectionReason rejectionReason,
    BigDecimal amount,
    String description,
    Instant createdAt,
    Instant updatedAt,
    List<Flag> flags) {

  /** One fraud rule that fired on the transaction. */
  public record Flag(String rule, String detail) {
    public static Flag from(FraudFlag f) {
      return new Flag(f.getRule(), f.getDetail());
    }
  }

  public TransactionResponse {
    // Idempotency records stored before this field existed deserialize it as null.
    flags = flags == null ? List.of() : List.copyOf(flags);
  }

  /** Without flags: for a transaction known not to be screened yet, such as a new one. */
  public static TransactionResponse from(Transaction t) {
    return from(t, List.of());
  }

  public static TransactionResponse from(Transaction t, List<FraudFlag> flags) {
    return new TransactionResponse(
        t.getId(),
        t.getAccountId(),
        t.getType(),
        t.getStatus(),
        t.getRejectionReason(),
        t.getAmount(),
        t.getDescription(),
        t.getCreatedAt(),
        t.getUpdatedAt(),
        flags.stream().map(Flag::from).toList());
  }
}

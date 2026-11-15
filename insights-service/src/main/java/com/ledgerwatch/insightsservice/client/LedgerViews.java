package com.ledgerwatch.insightsservice.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the ledger services return, as far as the insights tools need it. Unknown JSON fields are
 * ignored, so the services can add fields without breaking this one. Enum-valued fields are kept as
 * strings for the same reason.
 */
public final class LedgerViews {

  private LedgerViews() {}

  /** {@code GET /accounts/{id}} on account-service. */
  public record Account(
      UUID id,
      String ownerName,
      String status,
      BigDecimal balance,
      Instant createdAt,
      Instant updatedAt) {}

  /** One entry of {@code GET /transactions} on transaction-service. */
  public record Transaction(
      UUID id,
      UUID accountId,
      String type,
      String status,
      String rejectionReason,
      BigDecimal amount,
      String description,
      Instant createdAt,
      List<Flag> flags) {

    public record Flag(String rule, String detail) {}
  }

  /** One entry of {@code GET /accounts/{id}/flags} on transaction-service. */
  public record FraudFlag(
      UUID id,
      UUID transactionId,
      UUID accountId,
      String rule,
      String detail,
      boolean blocked,
      Instant flaggedAt) {}

  /** Spring Data's {@code PagedModel} JSON: {@code {"content": [...], "page": {...}}}. */
  public record Page<T>(List<T> content, PageMetadata page) {

    public record PageMetadata(int size, int number, long totalElements, int totalPages) {}
  }

  public enum TransactionType {
    CREDIT,
    DEBIT
  }

  public enum TransactionStatus {
    PENDING,
    POSTED,
    REJECTED,
    VOIDED
  }
}

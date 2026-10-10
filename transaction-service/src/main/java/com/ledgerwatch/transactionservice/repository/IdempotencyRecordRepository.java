package com.ledgerwatch.transactionservice.repository;

import com.ledgerwatch.transactionservice.domain.IdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

  Optional<IdempotencyRecord> findByCallerAndIdempotencyKey(String caller, String idempotencyKey);

  /**
   * Claims the key for this request. Returns 1 if claimed, or 0 if the key already belongs to a
   * committed request. If another transaction holds an uncommitted claim, this blocks until it
   * finishes. A failed insert would abort the surrounding Postgres transaction, so this uses ON
   * CONFLICT instead of catching a constraint violation.
   */
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO idempotency_record (caller, idempotency_key, request_hash)
          VALUES (:caller, :idempotencyKey, :requestHash)
          ON CONFLICT (caller, idempotency_key) DO NOTHING
          """)
  int claim(
      @Param("caller") String caller,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestHash") String requestHash);

  @Modifying
  @Query(
      """
      UPDATE IdempotencyRecord r
      SET r.transactionId = :transactionId, r.responseBody = :responseBody
      WHERE r.caller = :caller AND r.idempotencyKey = :idempotencyKey
      """)
  int complete(
      @Param("caller") String caller,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("transactionId") UUID transactionId,
      @Param("responseBody") String responseBody);
}

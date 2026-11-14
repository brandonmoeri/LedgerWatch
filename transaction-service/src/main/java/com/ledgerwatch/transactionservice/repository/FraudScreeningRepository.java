package com.ledgerwatch.transactionservice.repository;

import com.ledgerwatch.transactionservice.domain.FraudScreening;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FraudScreeningRepository extends JpaRepository<FraudScreening, UUID> {

  /**
   * Stores a verdict unless the transaction already has one. Returns 1 if stored, or 0 if another
   * screening got there first, in which case its verdict stands.
   */
  @Modifying
  @Transactional
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO fraud_screening (transaction_id, blocked, signals)
          VALUES (:transactionId, :blocked, CAST(:signals AS jsonb))
          ON CONFLICT (transaction_id) DO NOTHING
          """)
  int record(
      @Param("transactionId") UUID transactionId,
      @Param("blocked") boolean blocked,
      @Param("signals") String signals);
}

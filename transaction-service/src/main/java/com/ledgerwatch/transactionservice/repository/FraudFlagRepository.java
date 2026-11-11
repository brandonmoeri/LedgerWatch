package com.ledgerwatch.transactionservice.repository;

import com.ledgerwatch.transactionservice.domain.FraudFlag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, UUID> {

  /**
   * Stores one flag. Must run in the DB transaction that stored the screening: {@code now()} is the
   * transaction's start time, so {@code flagged_at} equals the screening's {@code screened_at}.
   */
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO fraud_flag (transaction_id, account_id, rule, detail, blocked)
          VALUES (:transactionId, :accountId, :rule, :detail, :blocked)
          """)
  int record(
      @Param("transactionId") UUID transactionId,
      @Param("accountId") UUID accountId,
      @Param("rule") String rule,
      @Param("detail") String detail,
      @Param("blocked") boolean blocked);

  Page<FraudFlag> findByAccountId(UUID accountId, Pageable pageable);
}

package com.ledgerwatch.transactionservice.repository;

import com.ledgerwatch.transactionservice.domain.Transaction;
import com.ledgerwatch.transactionservice.domain.TransactionRejectionReason;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository
    extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

  /**
   * Settles a PENDING transaction as POSTED. Any other status is left alone, so redelivering an
   * event can't undo a later change. Must run inside a transaction.
   */
  @Modifying
  @Query(
      """
      UPDATE Transaction t
      SET t.status = com.ledgerwatch.transactionservice.domain.TransactionStatus.POSTED,
          t.updatedAt = :at
      WHERE t.id = :id
        AND t.status = com.ledgerwatch.transactionservice.domain.TransactionStatus.PENDING
      """)
  int markPosted(@Param("id") UUID id, @Param("at") Instant at);

  /** Settles a PENDING transaction as REJECTED; see {@link #markPosted}. */
  @Modifying
  @Query(
      """
      UPDATE Transaction t
      SET t.status = com.ledgerwatch.transactionservice.domain.TransactionStatus.REJECTED,
          t.rejectionReason = :reason,
          t.updatedAt = :at
      WHERE t.id = :id
        AND t.status = com.ledgerwatch.transactionservice.domain.TransactionStatus.PENDING
      """)
  int markRejected(
      @Param("id") UUID id,
      @Param("reason") TransactionRejectionReason reason,
      @Param("at") Instant at);
}

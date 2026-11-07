package com.ledgerwatch.transactionservice.repository;

import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  List<OutboxEvent> findByAggregateIdOrderByCreatedAt(UUID aggregateId);

  /**
   * Locks up to {@code limit} pending events that are due at {@code now}, oldest first. Rows
   * another transaction already holds are skipped rather than waited on, so concurrent publishers
   * split the queue instead of serializing on it. Must run inside a transaction; the locks last
   * until it ends.
   */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT * FROM outbox_event
          WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at <= :now
          ORDER BY next_attempt_at, created_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
          """)
  List<OutboxEvent> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}

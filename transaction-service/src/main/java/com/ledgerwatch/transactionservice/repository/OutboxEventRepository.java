package com.ledgerwatch.transactionservice.repository;

import com.ledgerwatch.transactionservice.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  List<OutboxEvent> findByAggregateIdOrderByCreatedAt(UUID aggregateId);
}

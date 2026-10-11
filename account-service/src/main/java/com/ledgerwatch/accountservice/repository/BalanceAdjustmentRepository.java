package com.ledgerwatch.accountservice.repository;

import com.ledgerwatch.accountservice.domain.BalanceAdjustment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceAdjustmentRepository extends JpaRepository<BalanceAdjustment, UUID> {}

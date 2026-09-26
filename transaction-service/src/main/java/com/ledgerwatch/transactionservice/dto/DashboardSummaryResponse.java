package com.ledgerwatch.transactionservice.dto;

import com.ledgerwatch.transactionservice.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardSummaryResponse(
    List<BalancePoint> balanceOverTime, List<TypeSpending> spendByType) {

  public record BalancePoint(LocalDate date, BigDecimal balance) {}

  public record TypeSpending(TransactionType type, BigDecimal total, long count) {}
}

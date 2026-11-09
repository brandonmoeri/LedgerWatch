package com.ledgerwatch.transactionservice.repository;

import java.math.BigDecimal;

/**
 * Count and sum of a set of transaction amounts.
 *
 * @param count how many transactions
 * @param total their summed amount; zero when there are none
 */
public record AmountHistory(Long count, BigDecimal total) {
  public AmountHistory {
    count = count == null ? 0L : count;
    total = total == null ? BigDecimal.ZERO : total;
  }
}

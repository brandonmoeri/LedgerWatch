package com.ledgerwatch.transactionservice.metrics;

import com.ledgerwatch.transactionservice.domain.TransactionRejectionReason;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Counts transactions as the outbox publisher settles them:
 *
 * <ul>
 *   <li>{@code ledgerwatch.transactions.posted}, tagged {@code type} (CREDIT, DEBIT).
 *   <li>{@code ledgerwatch.transactions.rejected}, tagged {@code reason} (a {@link
 *       TransactionRejectionReason}).
 * </ul>
 *
 * <p>Every tag value is registered up front, so each series reports 0 until its first event rather
 * than nothing. CloudWatch graphs and alarms then see a real zero instead of missing data.
 */
@Component
public class TransactionMetrics {

  public static final String POSTED = "ledgerwatch.transactions.posted";
  public static final String REJECTED = "ledgerwatch.transactions.rejected";

  private final Map<TransactionType, Counter> posted = new EnumMap<>(TransactionType.class);
  private final Map<TransactionRejectionReason, Counter> rejected =
      new EnumMap<>(TransactionRejectionReason.class);

  public TransactionMetrics(MeterRegistry registry) {
    for (TransactionType type : TransactionType.values()) {
      posted.put(
          type,
          Counter.builder(POSTED)
              .description("Transactions settled as POSTED by account-service")
              .tag("type", type.name())
              .register(registry));
    }
    for (TransactionRejectionReason reason : TransactionRejectionReason.values()) {
      rejected.put(
          reason,
          Counter.builder(REJECTED)
              .description("Transactions settled as REJECTED by account-service")
              .tag("reason", reason.name())
              .register(registry));
    }
  }

  public void posted(TransactionType type) {
    posted.get(type).increment();
  }

  public void rejected(TransactionRejectionReason reason) {
    rejected.get(reason).increment();
  }
}

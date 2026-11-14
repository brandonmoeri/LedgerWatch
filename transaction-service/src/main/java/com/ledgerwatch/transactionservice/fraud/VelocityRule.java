package com.ledgerwatch.transactionservice.fraud;

import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Too many transactions on one account in a short window. Counts every transaction, whatever its
 * type or status, so a burst of attempts is caught even if earlier ones were rejected.
 */
@Component
public class VelocityRule implements FraudRule {

  public static final String NAME = "VELOCITY";

  private final TransactionRepository transactions;
  private final FraudProperties.Velocity config;

  public VelocityRule(TransactionRepository transactions, FraudProperties properties) {
    this.transactions = transactions;
    this.config = properties.velocity();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<FraudSignal> evaluate(FraudContext context) {
    // Counts the others and adds this one, rather than relying on its stored created_at (rounded
    // to microseconds by Postgres) falling inside a window that ends at its exact creation time.
    long count =
        1
            + transactions.countByAccountIdAndIdNotAndCreatedAtBetween(
                context.accountId(),
                context.transactionId(),
                context.createdAt().minus(config.window()),
                context.createdAt());
    if (count <= config.maxTransactions()) {
      return Optional.empty();
    }
    return Optional.of(
        signal(
            count
                + " transactions in "
                + config.window()
                + " (max "
                + config.maxTransactions()
                + ")"));
  }
}

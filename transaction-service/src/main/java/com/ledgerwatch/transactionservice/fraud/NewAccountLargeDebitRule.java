package com.ledgerwatch.transactionservice.fraud;

import com.ledgerwatch.transactionservice.client.AccountSummary;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A large DEBIT from an account that was only just opened. Asks account-service when the account
 * was created, but only for debits at or over the threshold.
 */
@Component
public class NewAccountLargeDebitRule implements FraudRule {

  public static final String NAME = "NEW_ACCOUNT_LARGE_DEBIT";

  private final FraudProperties.NewAccount config;

  public NewAccountLargeDebitRule(FraudProperties properties) {
    this.config = properties.newAccount();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<FraudSignal> evaluate(FraudContext context) {
    if (context.type() != TransactionType.DEBIT
        || context.amount().compareTo(config.largeDebitThreshold()) < 0) {
      return Optional.empty();
    }
    AccountSummary account = context.account();
    if (account.createdAt() == null) {
      throw new IllegalStateException("account-service returned no createdAt for " + account.id());
    }
    Duration age = Duration.between(account.createdAt(), context.createdAt());
    if (age.compareTo(config.maxAge()) >= 0) {
      return Optional.empty();
    }
    return Optional.of(
        signal(
            "DEBIT of "
                + context.amount().toPlainString()
                + " (threshold "
                + config.largeDebitThreshold().toPlainString()
                + ") on an account "
                + age.withNanos(0)
                + " old (minimum "
                + config.maxAge()
                + ")"));
  }
}

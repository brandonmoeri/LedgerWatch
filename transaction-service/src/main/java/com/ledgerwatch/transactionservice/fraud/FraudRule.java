package com.ledgerwatch.transactionservice.fraud;

import java.util.Optional;

/**
 * One fraud check (strategy). {@link FraudRuleEngine} runs every {@code FraudRule} bean against
 * each transaction before its balance change is sent to account-service.
 *
 * <p>Rules judge the transaction as of its {@link FraudContext#createdAt() creation time}, not the
 * time they run, so a late delivery sees the same window. A rule may throw (an account lookup
 * failure, for example); the publisher then handles it as it would a failed account-service call.
 */
public interface FraudRule {

  /** Stable identifier, stored with each signal and used as the {@code rule} metric tag. */
  String name();

  /** A signal if this rule considers the transaction suspicious, otherwise empty. */
  Optional<FraudSignal> evaluate(FraudContext context);

  default FraudSignal signal(String detail) {
    return new FraudSignal(name(), detail);
  }
}

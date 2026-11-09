package com.ledgerwatch.transactionservice.fraud;

import com.ledgerwatch.transactionservice.repository.AmountHistory;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * An amount far above the account's recent norm: more than {@code multiplier} times the average of
 * its POSTED transactions of the same type over the lookback period. Credits and debits are
 * averaged separately, so a monthly salary credit doesn't mask an unusual debit, or the reverse.
 */
@Component
public class AmountSpikeRule implements FraudRule {

  public static final String NAME = "AMOUNT_SPIKE";

  private final TransactionRepository transactions;
  private final FraudProperties.AmountSpike config;

  public AmountSpikeRule(TransactionRepository transactions, FraudProperties properties) {
    this.transactions = transactions;
    this.config = properties.amountSpike();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<FraudSignal> evaluate(FraudContext context) {
    AmountHistory history =
        transactions.postedAmountHistory(
            context.accountId(),
            context.type(),
            context.createdAt().minus(config.lookback()),
            context.createdAt());
    if (history.count() < config.minHistory()) {
      return Optional.empty();
    }
    // amount > multiplier * (total / count), compared without dividing.
    BigDecimal scaledAmount = context.amount().multiply(BigDecimal.valueOf(history.count()));
    if (scaledAmount.compareTo(history.total().multiply(config.multiplier())) <= 0) {
      return Optional.empty();
    }
    BigDecimal average =
        history.total().divide(BigDecimal.valueOf(history.count()), 2, RoundingMode.HALF_EVEN);
    return Optional.of(
        signal(
            "amount "
                + context.amount().toPlainString()
                + " exceeds "
                + config.multiplier().stripTrailingZeros().toPlainString()
                + "x the "
                + config.lookback()
                + " "
                + context.type()
                + " average of "
                + average.toPlainString()
                + " over "
                + history.count()
                + " transactions"));
  }
}

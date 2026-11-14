package com.ledgerwatch.transactionservice.fraud;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the fraud rules ({@code fraud.*}).
 *
 * @param enabled whether transactions are screened at all
 * @param mode {@code ENFORCE} rejects a transaction when any rule fires; {@code MONITOR} records
 *     the signals and posts it anyway, for trying out thresholds on live traffic
 */
@Validated
@ConfigurationProperties("fraud")
public record FraudProperties(
    boolean enabled,
    @NotNull Mode mode,
    @NotNull @Valid Velocity velocity,
    @NotNull @Valid AmountSpike amountSpike,
    @NotNull @Valid NewAccount newAccount) {

  public enum Mode {
    ENFORCE,
    MONITOR
  }

  /**
   * {@link VelocityRule}: fires when the account has more than {@code maxTransactions}
   * transactions, of any type or status and counting this one, in the {@code window} up to this
   * one's creation.
   */
  public record Velocity(@Positive int maxTransactions, @NotNull Duration window) {}

  /**
   * {@link AmountSpikeRule}: fires when the amount exceeds {@code multiplier} times the average of
   * the account's POSTED transactions of the same type in the {@code lookback} before it. Skipped
   * with fewer than {@code minHistory} such transactions, where an average means little.
   */
  public record AmountSpike(
      @NotNull @Positive BigDecimal multiplier,
      @NotNull Duration lookback,
      @Positive int minHistory) {}

  /**
   * {@link NewAccountLargeDebitRule}: fires on a DEBIT of at least {@code largeDebitThreshold} from
   * an account younger than {@code maxAge} when the transaction was created.
   */
  public record NewAccount(
      @NotNull Duration maxAge, @NotNull @Positive BigDecimal largeDebitThreshold) {}
}

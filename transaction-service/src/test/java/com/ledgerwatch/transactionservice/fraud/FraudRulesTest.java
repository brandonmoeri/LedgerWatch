package com.ledgerwatch.transactionservice.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ledgerwatch.transactionservice.client.AccountSummary;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.repository.AmountHistory;
import com.ledgerwatch.transactionservice.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class FraudRulesTest {

  static final Instant CREATED_AT = Instant.parse("2026-11-01T12:00:00Z");
  static final UUID ACCOUNT_ID = UUID.randomUUID();
  static final FraudProperties PROPERTIES =
      new FraudProperties(
          true,
          FraudProperties.Mode.ENFORCE,
          new FraudProperties.Velocity(10, Duration.ofMinutes(10)),
          new FraudProperties.AmountSpike(new BigDecimal("3"), Duration.ofDays(30), 3),
          new FraudProperties.NewAccount(Duration.ofHours(24), new BigDecimal("1000")));

  @Mock TransactionRepository transactions;

  static FraudContext context(TransactionType type, String amount) {
    return context(
        type,
        amount,
        () -> {
          throw new AssertionError("account should not be fetched");
        });
  }

  static FraudContext context(
      TransactionType type, String amount, Supplier<AccountSummary> account) {
    return new FraudContext(
        UUID.randomUUID(), ACCOUNT_ID, type, new BigDecimal(amount), CREATED_AT, account);
  }

  static Supplier<AccountSummary> accountAged(Duration age) {
    return () -> new AccountSummary(ACCOUNT_ID, "ACTIVE", CREATED_AT.minus(age));
  }

  @Nested
  class Velocity {

    VelocityRule rule() {
      return new VelocityRule(transactions, PROPERTIES);
    }

    FraudContext withOthers(long others) {
      FraudContext context = context(TransactionType.CREDIT, "1");
      when(transactions.countByAccountIdAndIdNotAndCreatedAtBetween(
              ACCOUNT_ID,
              context.transactionId(),
              CREATED_AT.minus(Duration.ofMinutes(10)),
              CREATED_AT))
          .thenReturn(others);
      return context;
    }

    @Test
    void atTheLimitCountingThisOne_isClear() {
      assertThat(rule().evaluate(withOthers(9))).isEmpty();
    }

    @Test
    void overTheLimit_fires() {
      assertThat(rule().evaluate(withOthers(10)))
          .hasValueSatisfying(
              s -> {
                assertThat(s.rule()).isEqualTo("VELOCITY");
                assertThat(s.detail()).isEqualTo("11 transactions in PT10M (max 10)");
              });
    }
  }

  @Nested
  class AmountSpike {

    AmountSpikeRule rule() {
      return new AmountSpikeRule(transactions, PROPERTIES);
    }

    void history(TransactionType type, long count, String total) {
      when(transactions.postedAmountHistory(
              ACCOUNT_ID, type, CREATED_AT.minus(Duration.ofDays(30)), CREATED_AT))
          .thenReturn(new AmountHistory(count, total == null ? null : new BigDecimal(total)));
    }

    @Test
    void moreThanThreeTimesTheAverage_fires() {
      history(TransactionType.DEBIT, 4, "400.00"); // average 100
      assertThat(rule().evaluate(context(TransactionType.DEBIT, "300.01")))
          .hasValueSatisfying(
              s -> {
                assertThat(s.rule()).isEqualTo("AMOUNT_SPIKE");
                assertThat(s.detail())
                    .isEqualTo(
                        "amount 300.01 exceeds 3x the PT720H DEBIT average of 100.00 over 4"
                            + " transactions");
              });
    }

    @Test
    void exactlyThreeTimesTheAverage_isClear() {
      history(TransactionType.DEBIT, 4, "400.00");
      assertThat(rule().evaluate(context(TransactionType.DEBIT, "300.00"))).isEmpty();
    }

    @Test
    void comparesAgainstTheSameTypeOnly() {
      history(TransactionType.CREDIT, 3, "30.00");
      rule().evaluate(context(TransactionType.CREDIT, "50"));
      verify(transactions)
          .postedAmountHistory(eq(ACCOUNT_ID), eq(TransactionType.CREDIT), any(), any());
    }

    @Test
    void tooLittleHistory_isClear() {
      history(TransactionType.DEBIT, 2, "2.00");
      assertThat(rule().evaluate(context(TransactionType.DEBIT, "1000000"))).isEmpty();
    }

    @Test
    void noHistory_isClear() {
      history(TransactionType.DEBIT, 0, null);
      assertThat(rule().evaluate(context(TransactionType.DEBIT, "1000000"))).isEmpty();
    }
  }

  @Nested
  class NewAccountLargeDebit {

    final NewAccountLargeDebitRule rule = new NewAccountLargeDebitRule(PROPERTIES);

    @Test
    void largeDebitOnYoungAccount_fires() {
      assertThat(
              rule.evaluate(
                  context(TransactionType.DEBIT, "1000", accountAged(Duration.ofHours(3)))))
          .hasValueSatisfying(
              s -> {
                assertThat(s.rule()).isEqualTo("NEW_ACCOUNT_LARGE_DEBIT");
                assertThat(s.detail())
                    .isEqualTo(
                        "DEBIT of 1000 (threshold 1000) on an account PT3H old (minimum PT24H)");
              });
    }

    @Test
    void accountExactly24hOld_isClear() {
      assertThat(
              rule.evaluate(
                  context(TransactionType.DEBIT, "5000", accountAged(Duration.ofHours(24)))))
          .isEmpty();
    }

    @Test
    void smallDebit_doesNotFetchAccount() {
      assertThat(rule.evaluate(context(TransactionType.DEBIT, "999.99"))).isEmpty();
    }

    @Test
    void largeCredit_doesNotFetchAccount() {
      assertThat(rule.evaluate(context(TransactionType.CREDIT, "50000"))).isEmpty();
    }

    @Test
    void missingCreatedAt_throws() {
      FraudContext context =
          context(
              TransactionType.DEBIT, "5000", () -> new AccountSummary(ACCOUNT_ID, "ACTIVE", null));
      assertThatThrownBy(() -> rule.evaluate(context)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void contextFetchesAccountAtMostOnce() {
    AtomicInteger calls = new AtomicInteger();
    FraudContext context =
        context(
            TransactionType.DEBIT,
            "1",
            () -> {
              calls.incrementAndGet();
              return new AccountSummary(ACCOUNT_ID, "ACTIVE", CREATED_AT);
            });

    context.account();
    context.account();

    assertThat(calls).hasValue(1);
  }
}

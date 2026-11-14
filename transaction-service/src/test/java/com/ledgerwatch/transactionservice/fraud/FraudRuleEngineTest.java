package com.ledgerwatch.transactionservice.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.domain.FraudScreening;
import com.ledgerwatch.transactionservice.domain.TransactionType;
import com.ledgerwatch.transactionservice.repository.FraudFlagRepository;
import com.ledgerwatch.transactionservice.repository.FraudScreeningRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class FraudRuleEngineTest {

  private static final UUID TX_ID = UUID.randomUUID();
  private static final UUID ACCOUNT_ID = UUID.randomUUID();

  @Mock FraudScreeningRepository screenings;
  @Mock FraudFlagRepository flags;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  /** A rule that always answers the same, and counts how often it ran. */
  static final class FixedRule implements FraudRule {
    private final String name;
    private final boolean fires;
    int calls;

    FixedRule(String name, boolean fires) {
      this.name = name;
      this.fires = fires;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Optional<FraudSignal> evaluate(FraudContext context) {
      calls++;
      return fires ? Optional.of(signal(name + " fired")) : Optional.empty();
    }
  }

  private static FraudProperties properties(boolean enabled, FraudProperties.Mode mode) {
    return new FraudProperties(
        enabled,
        mode,
        FraudRulesTest.PROPERTIES.velocity(),
        FraudRulesTest.PROPERTIES.amountSpike(),
        FraudRulesTest.PROPERTIES.newAccount());
  }

  private FraudRuleEngine engine(FraudProperties.Mode mode, FraudRule... rules) {
    return new FraudRuleEngine(
        List.of(rules),
        screenings,
        flags,
        TransactionOperations.withoutTransaction(),
        properties(true, mode),
        objectMapper,
        meterRegistry);
  }

  private static FraudContext context() {
    return new FraudContext(
        TX_ID,
        ACCOUNT_ID,
        TransactionType.DEBIT,
        BigDecimal.TEN,
        Instant.parse("2026-11-01T12:00:00Z"),
        () -> {
          throw new AssertionError("not needed");
        });
  }

  private static FraudScreening stored(boolean blocked, String signalsJson) {
    // The no-arg constructor is protected, for JPA.
    FraudScreening screening = BeanUtils.instantiateClass(FraudScreening.class);
    ReflectionTestUtils.setField(screening, "transactionId", TX_ID);
    ReflectionTestUtils.setField(screening, "blocked", blocked);
    ReflectionTestUtils.setField(screening, "signals", signalsJson);
    return screening;
  }

  private double signals(String rule) {
    return meterRegistry.get(FraudRuleEngine.SIGNALS_METRIC).tag("rule", rule).counter().count();
  }

  @Test
  void runsEveryRule_andBlocksInEnforceMode() {
    FixedRule a = new FixedRule("A", true);
    FixedRule b = new FixedRule("B", false);
    FixedRule c = new FixedRule("C", true);
    when(screenings.findById(TX_ID)).thenReturn(Optional.empty());
    when(screenings.record(eq(TX_ID), eq(true), anyString())).thenReturn(1);

    FraudVerdict verdict = engine(FraudProperties.Mode.ENFORCE, a, b, c).screen(context());

    assertThat(verdict.blocked()).isTrue();
    assertThat(verdict.signals()).extracting(FraudSignal::rule).containsExactly("A", "C");
    assertThat(List.of(a.calls, b.calls, c.calls)).containsOnly(1);
    verify(screenings)
        .record(
            TX_ID,
            true,
            "[{\"rule\":\"A\",\"detail\":\"A fired\"},{\"rule\":\"C\",\"detail\":\"C fired\"}]");
    verify(flags).record(TX_ID, ACCOUNT_ID, "A", "A fired", true);
    verify(flags).record(TX_ID, ACCOUNT_ID, "C", "C fired", true);
    verifyNoMoreInteractions(flags);
    assertThat(signals("A")).isEqualTo(1);
    assertThat(signals("B")).isZero();
  }

  @Test
  void monitorMode_flagsWithoutBlocking() {
    when(screenings.findById(TX_ID)).thenReturn(Optional.empty());
    when(screenings.record(eq(TX_ID), eq(false), anyString())).thenReturn(1);

    FraudVerdict verdict =
        engine(FraudProperties.Mode.MONITOR, new FixedRule("A", true)).screen(context());

    assertThat(verdict.blocked()).isFalse();
    assertThat(verdict.signals()).hasSize(1);
    verify(flags).record(TX_ID, ACCOUNT_ID, "A", "A fired", false);
  }

  @Test
  void noSignals_isClearAndStillRecorded() {
    when(screenings.findById(TX_ID)).thenReturn(Optional.empty());
    when(screenings.record(TX_ID, false, "[]")).thenReturn(1);

    FraudVerdict verdict =
        engine(FraudProperties.Mode.ENFORCE, new FixedRule("A", false)).screen(context());

    assertThat(verdict).isEqualTo(FraudVerdict.CLEAR);
    verifyNoInteractions(flags);
  }

  @Test
  void alreadyScreened_returnsStoredVerdictWithoutRerunningRules() {
    FixedRule rule = new FixedRule("A", false);
    when(screenings.findById(TX_ID))
        .thenReturn(Optional.of(stored(true, "[{\"rule\":\"A\",\"detail\":\"earlier\"}]")));

    FraudVerdict verdict = engine(FraudProperties.Mode.ENFORCE, rule).screen(context());

    assertThat(verdict.blocked()).isTrue();
    assertThat(verdict.signals()).containsExactly(new FraudSignal("A", "earlier"));
    assertThat(rule.calls).isZero();
    verify(screenings, never()).record(any(), anyBoolean(), any());
    verifyNoInteractions(flags);
    assertThat(signals("A")).isZero();
  }

  @Test
  void concurrentScreeningWins_returnsItsVerdictAndDoesNotCount() {
    when(screenings.findById(TX_ID))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(stored(false, "[]")));
    when(screenings.record(eq(TX_ID), eq(true), anyString())).thenReturn(0);

    FraudVerdict verdict =
        engine(FraudProperties.Mode.ENFORCE, new FixedRule("A", true)).screen(context());

    assertThat(verdict).isEqualTo(FraudVerdict.CLEAR);
    verifyNoInteractions(flags);
    assertThat(signals("A")).isZero();
  }

  @Test
  void ruleFailure_propagatesAndRecordsNothing() {
    FraudRule failing =
        new FraudRule() {
          @Override
          public String name() {
            return "FAILING";
          }

          @Override
          public Optional<FraudSignal> evaluate(FraudContext context) {
            throw new IllegalStateException("boom");
          }
        };
    when(screenings.findById(TX_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> engine(FraudProperties.Mode.ENFORCE, failing).screen(context()))
        .hasMessage("boom");
    verify(screenings, never()).record(any(), anyBoolean(), any());
    verifyNoInteractions(flags);
  }

  @Test
  void disabled_isClearWithoutTouchingRulesOrStorage() {
    FixedRule rule = new FixedRule("A", true);
    FraudRuleEngine engine =
        new FraudRuleEngine(
            List.of(rule),
            screenings,
            flags,
            TransactionOperations.withoutTransaction(),
            properties(false, FraudProperties.Mode.ENFORCE),
            objectMapper,
            meterRegistry);

    assertThat(engine.screen(context())).isEqualTo(FraudVerdict.CLEAR);
    assertThat(rule.calls).isZero();
    verifyNoInteractions(screenings, flags);
  }
}

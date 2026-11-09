package com.ledgerwatch.transactionservice.fraud;

import java.util.List;
import java.util.stream.Collectors;

/** The fraud rules blocked a transaction; it is rejected without touching the balance. */
public class SuspectedFraudException extends RuntimeException {

  private final transient List<FraudSignal> signals;

  public SuspectedFraudException(List<FraudSignal> signals) {
    super(
        "Blocked by fraud rules: "
            + signals.stream()
                .map(s -> s.rule() + " (" + s.detail() + ")")
                .collect(Collectors.joining("; ")));
    this.signals = List.copyOf(signals);
  }

  public List<FraudSignal> getSignals() {
    return signals;
  }
}

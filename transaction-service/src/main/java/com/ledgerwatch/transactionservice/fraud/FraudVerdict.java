package com.ledgerwatch.transactionservice.fraud;

import java.util.List;

/**
 * The outcome of screening one transaction.
 *
 * @param signals every rule that fired; empty if none did
 * @param blocked whether the transaction must be rejected rather than posted. Only true in {@code
 *     ENFORCE} mode with at least one signal.
 */
public record FraudVerdict(List<FraudSignal> signals, boolean blocked) {
  public static final FraudVerdict CLEAR = new FraudVerdict(List.of(), false);

  public FraudVerdict {
    signals = List.copyOf(signals);
  }
}

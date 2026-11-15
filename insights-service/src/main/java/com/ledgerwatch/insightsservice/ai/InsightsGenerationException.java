package com.ledgerwatch.insightsservice.ai;

/** Claude answered, but not with a usable reply: it refused, or ran out of output tokens. */
public class InsightsGenerationException extends RuntimeException {

  private final String stopReason;

  public InsightsGenerationException(String stopReason, String message) {
    super(message);
    this.stopReason = stopReason;
  }

  public String getStopReason() {
    return stopReason;
  }
}

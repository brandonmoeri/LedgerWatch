package com.ledgerwatch.transactionservice.fraud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerwatch.transactionservice.domain.FraudScreening;
import com.ledgerwatch.transactionservice.repository.FraudScreeningRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link FraudRule} bean against a transaction and stores the verdict.
 *
 * <p>All rules run, rather than stopping at the first hit, so a reviewer sees every reason. The
 * verdict is stored once per transaction ({@code fraud_screening}); screening the same transaction
 * again, as a redelivered outbox event does, returns the stored verdict without re-running the
 * rules. Otherwise a transaction already posted on an earlier attempt could be rejected on a replay
 * because, say, its account's 30-day average had moved in between.
 *
 * <p>Counts {@code ledgerwatch.fraud.signals}, tagged {@code rule}, once per stored signal. Every
 * rule's series is registered up front so it reports 0 rather than nothing.
 */
@Component
@EnableConfigurationProperties(FraudProperties.class)
public class FraudRuleEngine {

  public static final String SIGNALS_METRIC = "ledgerwatch.fraud.signals";
  private static final Logger log = LoggerFactory.getLogger(FraudRuleEngine.class);
  private static final TypeReference<List<FraudSignal>> SIGNAL_LIST = new TypeReference<>() {};

  private final List<FraudRule> rules;
  private final FraudScreeningRepository screenings;
  private final FraudProperties properties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry registry;

  public FraudRuleEngine(
      List<FraudRule> rules,
      FraudScreeningRepository screenings,
      FraudProperties properties,
      ObjectMapper objectMapper,
      MeterRegistry registry) {
    this.rules = List.copyOf(rules);
    this.screenings = screenings;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.registry = registry;
    this.rules.forEach(rule -> counter(rule.name()));
  }

  /**
   * The verdict on this transaction: the stored one if it was screened before, otherwise a fresh
   * one, which is stored. Always {@link FraudVerdict#CLEAR} while {@code fraud.enabled} is false.
   * Exceptions thrown by a rule propagate, and nothing is stored.
   */
  public FraudVerdict screen(FraudContext context) {
    if (!properties.enabled()) {
      return FraudVerdict.CLEAR;
    }
    Optional<FraudVerdict> stored = storedVerdict(context);
    if (stored.isPresent()) {
      return stored.get();
    }

    List<FraudSignal> signals =
        rules.stream().map(rule -> rule.evaluate(context)).flatMap(Optional::stream).toList();
    boolean blocked = !signals.isEmpty() && properties.mode() == FraudProperties.Mode.ENFORCE;

    if (screenings.record(context.transactionId(), blocked, toJson(signals)) == 0) {
      // A concurrent screening of the same transaction stored its verdict first.
      return storedVerdict(context)
          .orElseThrow(() -> new IllegalStateException("Fraud screening vanished"));
    }
    signals.forEach(signal -> counter(signal.rule()).increment());
    if (!signals.isEmpty()) {
      log.warn(
          "Transaction {} {} by fraud rules: {}",
          context.transactionId(),
          blocked ? "blocked" : "flagged",
          signals);
    }
    return new FraudVerdict(signals, blocked);
  }

  private Optional<FraudVerdict> storedVerdict(FraudContext context) {
    return screenings.findById(context.transactionId()).map(this::toVerdict);
  }

  private FraudVerdict toVerdict(FraudScreening screening) {
    try {
      return new FraudVerdict(
          objectMapper.readValue(screening.getSignals(), SIGNAL_LIST), screening.isBlocked());
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String toJson(List<FraudSignal> signals) {
    try {
      return objectMapper.writeValueAsString(signals);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Counter counter(String rule) {
    return Counter.builder(SIGNALS_METRIC)
        .description("Fraud rule hits, in ENFORCE or MONITOR mode")
        .tag("rule", rule)
        .register(registry);
  }
}

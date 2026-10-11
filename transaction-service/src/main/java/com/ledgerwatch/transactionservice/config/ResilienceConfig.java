package com.ledgerwatch.transactionservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

  private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

  /** Logs every circuit-breaker state change, e.g. CLOSED -> OPEN when account-service degrades. */
  @Bean
  public RegistryEventConsumer<CircuitBreaker> circuitBreakerTransitionLogger() {
    return new RegistryEventConsumer<>() {
      @Override
      public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) {
        CircuitBreaker breaker = event.getAddedEntry();
        breaker
            .getEventPublisher()
            .onStateTransition(
                e ->
                    log.warn("Circuit breaker '{}' {}", breaker.getName(), e.getStateTransition()));
      }

      @Override
      public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> event) {}

      @Override
      public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> event) {}
    };
  }
}
